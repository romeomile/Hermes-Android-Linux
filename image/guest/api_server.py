#!/usr/bin/env python3
"""Control API for the Hermes Android Linux guest.

Listens on 0.0.0.0:7080 inside the VM. QEMU forwards the device's
127.0.0.1:7080 here, so the Android app can drive the guest: read the engine
state, run shell commands, and control the Hermes agent that also runs here.

Auth: every endpoint except /health requires
    Authorization: Bearer <token>
where <token> is generated on the device and handed to the guest through the
kernel command line (api_token=...). Nothing is baked into the image.
"""
from __future__ import annotations

import asyncio
import os
import shutil
import socket
import subprocess
import time
from pathlib import Path

from fastapi import Depends, FastAPI, Header, HTTPException
from pydantic import BaseModel

AGENT_PORT = int(os.environ.get("HERMES_AGENT_PORT", "8642"))
HERMES_HOME = Path(os.environ.get("HERMES_HOME", "/root/.hermes"))


def find_hermes() -> str:
    """Resolve the agent launcher: pip's bin directory is distro-dependent."""
    override = os.environ.get("HERMES_BIN", "").strip()
    if override and Path(override).exists():
        return override
    found = shutil.which("hermes")
    if found:
        return found
    for candidate in ("/usr/bin/hermes", "/usr/local/bin/hermes"):
        if Path(candidate).exists():
            return candidate
    return "/usr/bin/hermes"


HERMES_BIN = find_hermes()
AGENT_LOG = Path("/var/log/hermes-agent.log")
AGENT_PID = Path("/var/run/hermes-agent.pid")
START_AGENT = Path("/bootstrap/start_agent.sh")
TOKEN_FILE = Path("/bootstrap/token")
MAX_EXEC_TIMEOUT = 1800


def load_token() -> str:
    token = os.environ.get("API_TOKEN", "").strip()
    if token:
        return token
    try:
        for part in Path("/proc/cmdline").read_text().split():
            if part.startswith("api_token="):
                return part.split("=", 1)[1].strip()
    except OSError:
        pass
    try:
        return TOKEN_FILE.read_text().strip()
    except OSError:
        return ""


API_TOKEN = load_token()

app = FastAPI(title="Hermes Linux guest control API", version="1.0")


def require_auth(authorization: str | None = Header(None)) -> None:
    if not API_TOKEN:
        raise HTTPException(status_code=503, detail="No API token in the guest")
    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(status_code=401, detail="Missing Authorization header")
    if authorization[len("Bearer "):] != API_TOKEN:
        raise HTTPException(status_code=401, detail="Invalid token")


def port_open(port: int, host: str = "127.0.0.1", timeout: float = 1.0) -> bool:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.settimeout(timeout)
        return sock.connect_ex((host, port)) == 0


def agent_pid() -> int:
    try:
        return int(AGENT_PID.read_text().strip())
    except (OSError, ValueError):
        return 0


def agent_running() -> bool:
    """True only when the agent's API port is actually served.

    The pidfile is not evidence on its own: guest pids are reused, so `kill -0` on a recycled pid
    reports an agent that exited long ago. That made `/agent/start` a silent no-op and let a client's
    readiness check pass while nothing was bound to 8642 - the port decides.
    """
    if port_open(AGENT_PORT):
        return True
    pid = agent_pid()
    if pid:
        try:
            os.kill(pid, 0)
        except OSError:
            pass
    return False


_AGENT_VERSION_CACHE = None  # str once resolved


def agent_version() -> str:
    """`hermes --version`, cached for the process lifetime.

    It imports the whole agent, which takes tens of seconds on a phone, so it must never be called
    directly from an async handler: the blocking run freezes the control API's only event loop and every
    other request with it (measured: 52 s for /agent/status, with concurrent /health never answering).
    Call it through asyncio.to_thread and keep the result.
    """
    global _AGENT_VERSION_CACHE
    if _AGENT_VERSION_CACHE is not None:
        return _AGENT_VERSION_CACHE
    if not Path(HERMES_BIN).exists():
        _AGENT_VERSION_CACHE = ""
        return ""
    try:
        out = subprocess.run([HERMES_BIN, "--version"], capture_output=True,
                             text=True, timeout=180)
        _AGENT_VERSION_CACHE = (out.stdout or out.stderr).strip().splitlines()[0]
    except Exception:
        _AGENT_VERSION_CACHE = ""
    return _AGENT_VERSION_CACHE


def run_sh(cmd: str, timeout: int = 60) -> dict:
    try:
        done = subprocess.run(["/bin/sh", "-c", cmd], capture_output=True,
                              text=True, timeout=timeout)
        return {"stdout": done.stdout, "stderr": done.stderr,
                "exitCode": done.returncode, "timedOut": False}
    except subprocess.TimeoutExpired as exc:
        return {"stdout": exc.stdout or "", "stderr": exc.stderr or "",
                "exitCode": 124, "timedOut": True}


def tail(path: Path, lines: int) -> str:
    if not path.exists():
        return ""
    try:
        data = path.read_text(errors="replace").splitlines()
    except OSError:
        return ""
    return "\n".join(data[-max(1, min(lines, 2000)):])


class ExecRequest(BaseModel):
    cmd: str
    timeout: int = 60


class EnvEntry(BaseModel):
    name: str
    value: str


class AgentConfigRequest(BaseModel):
    env: list[EnvEntry] = []
    settings: dict[str, str] = {}


@app.get("/health")
async def health() -> dict:
    return {"status": "ok", "agent": "running" if agent_running() else "stopped",
            "agentPort": AGENT_PORT, "script": START_AGENT.exists()}


@app.post("/vm/exec", dependencies=[Depends(require_auth)])
async def vm_exec(req: ExecRequest) -> dict:
    timeout = max(1, min(req.timeout, MAX_EXEC_TIMEOUT))
    return run_sh(req.cmd, timeout)


@app.get("/agent/status", dependencies=[Depends(require_auth)])
async def agent_status() -> dict:
    installed = Path(HERMES_BIN).exists()
    return {"installed": installed, "running": agent_running(),
            "port": AGENT_PORT, "home": str(HERMES_HOME),
            "pid": agent_pid(),
            "version": (await asyncio.to_thread(agent_version)) if installed else ""}


@app.post("/agent/start", dependencies=[Depends(require_auth)])
async def agent_start() -> dict:
    if not Path(HERMES_BIN).exists():
        raise HTTPException(status_code=500, detail="Hermes is not installed in the guest")
    if agent_running():
        return {"ok": True, "started": False}
    # start_agent.sh exits early while the pid in the pidfile is alive, and guest pids are reused:
    # a stale file would make this call a silent no-op. The file is only cleared when the port is
    # closed, which `agent_running()` has just established.
    try:
        AGENT_PID.unlink()
    except OSError:
        pass
    proc = subprocess.run(["/bin/sh", str(START_AGENT)], capture_output=True,
                          text=True, timeout=120)
    deadline = time.time() + 240
    while time.time() < deadline:
        if agent_running():
            return {"ok": True, "started": True, "log": tail(AGENT_LOG, 20)}
        time.sleep(1)
    return {"ok": False, "started": False,
            "error": "agent was not ready within 240s",
            "stderr": proc.stderr, "log": tail(AGENT_LOG, 40)}


@app.post("/agent/stop", dependencies=[Depends(require_auth)])
async def agent_stop() -> dict:
    """Stop the guest-local agent by its pid file (never by process pattern)."""
    pid = agent_pid()
    if pid:
        try:
            os.kill(pid, 15)
        except OSError:
            pass
    try:
        AGENT_PID.unlink()
    except OSError:
        pass
    deadline = time.time() + 30
    while time.time() < deadline and port_open(AGENT_PORT, timeout=0.3):
        time.sleep(1)
    return {"ok": True, "running": agent_running()}


@app.get("/agent/log", dependencies=[Depends(require_auth)])
async def agent_log(lines: int = 200) -> dict:
    return {"log": tail(AGENT_LOG, lines)}


@app.get("/control/log", dependencies=[Depends(require_auth)])
async def control_log(lines: int = 200) -> dict:
    return {"log": tail(Path("/var/log/control-api.log"), lines)}


@app.post("/agent/config", dependencies=[Depends(require_auth)])
async def agent_config(req: AgentConfigRequest) -> dict:
    env_path = HERMES_HOME / ".env"
    HERMES_HOME.mkdir(parents=True, exist_ok=True)

    # Merge credentials into .env without disturbing anything else that is there.
    existing: list[str] = []
    if env_path.exists():
        existing = env_path.read_text().splitlines()
    wanted = {entry.name.strip(): entry.value for entry in req.env if entry.name.strip()}
    seen: set[str] = set()
    merged: list[str] = []
    for line in existing:
        name = line.split("=", 1)[0].strip() if "=" in line else ""
        if name and name in wanted:
            merged.append("%s=%s" % (name, wanted[name]))
            seen.add(name)
        else:
            merged.append(line)
    for name, value in wanted.items():
        if name not in seen:
            merged.append("%s=%s" % (name, value))
    env_path.write_text("\n".join(merged).strip() + "\n")
    env_path.chmod(0o600)

    # Apply behavioural settings through the CLI so config.yaml stays valid.
    applied: list[str] = []
    env = {**os.environ, "HERMES_HOME": str(HERMES_HOME)}
    for key, value in req.settings.items():
        try:
            result = subprocess.run([HERMES_BIN, "config", "set", key, str(value)],
                                    capture_output=True, text=True, timeout=180, env=env)
            applied.append(key if result.returncode == 0 else "%s (failed)" % key)
        except Exception as exc:  # keep the endpoint useful even if one key fails
            applied.append("%s (error: %s)" % (key, exc))

    return {"ok": True, "env": sorted(wanted), "settings": applied, "restartRequired": True}


if __name__ == "__main__":
    import uvicorn

    # 0.0.0.0 on purpose: SLIRP forwards device traffic to the guest's eth0, not to its loopback.
    uvicorn.run(app, host="0.0.0.0", port=int(os.environ.get("CONTROL_PORT", "7080")),
                log_level="info")
