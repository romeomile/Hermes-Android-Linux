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
# Written by /agent/stop so the supervisor below leaves a deliberately stopped agent alone.
AGENT_HOLD = Path("/var/run/hermes-agent.hold")
CONTROL_LOG = Path("/var/log/hermes-control.log")
# How long a launched gateway may hold a pid without serving its port before the supervisor replaces
# it, and how many starts it may attempt in half an hour. Binding under emulation takes minutes -
# measured ~215 s on a build host, more on a phone - so this has to be generous.
START_GRACE_SECONDS = 600
MAX_ATTEMPTS = 3
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
    return port_open(AGENT_PORT)


def pid_alive(pid: int) -> bool:
    """True for a live process, false for a dead one *and for a zombie*.

    A `kill -9`ed gateway is often left as a zombie by the guest's init, and `kill -0` on a zombie
    succeeds - which would make the pidfile look like a running agent when nothing serves the port.
    """
    if not pid:
        return False
    try:
        with open("/proc/%d/stat" % pid) as handle:
            state = handle.read().rsplit(")", 1)[1].split()[0]
        if state == "Z":
            return False
        os.kill(pid, 0)
        return True
    except (OSError, IndexError):
        return False


def signal_agent(sig: int) -> None:
    pid = agent_pid()
    if pid:
        try:
            os.kill(pid, sig)
        except OSError:
            pass


def wait_port_open(seconds: float) -> bool:
    deadline = time.time() + seconds
    while time.time() < deadline:
        if port_open(AGENT_PORT):
            return True
        time.sleep(1)
    return port_open(AGENT_PORT)


def wait_port_closed(seconds: float) -> bool:
    deadline = time.time() + seconds
    while time.time() < deadline:
        if not port_open(AGENT_PORT):
            return True
        time.sleep(1)
    return not port_open(AGENT_PORT)


def clear_stale_agent() -> None:
    """Signal away a process that holds the pidfile without serving the port.

    A gateway that is alive but deaf is worse than a dead one: `start_agent.sh` exits early while that
    pid lives, so every later start is a silent no-op and the port stays dark.
    """
    pid = agent_pid()
    if pid and pid_alive(pid):
        signal_agent(15)
        deadline = time.time() + 10
        while pid_alive(pid) and time.time() < deadline:
            time.sleep(1)
        if pid_alive(pid):
            signal_agent(9)
            deadline = time.time() + 20
            while pid_alive(pid) and time.time() < deadline:
                time.sleep(1)
    try:
        AGENT_PID.unlink()
    except OSError:
        pass


def launch_agent() -> str:
    """Run the boot script and report what it said. The caller waits for the port, not for this."""
    proc = subprocess.run(["/bin/sh", str(START_AGENT)], capture_output=True, text=True, timeout=120)
    return (proc.stdout or "") + (proc.stderr or "")


def note(text: str) -> None:
    try:
        with CONTROL_LOG.open("a") as handle:
            handle.write("%s  %s\n" % (time.strftime("%Y-%m-%d %H:%M:%S"), text))
    except OSError:
        pass


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
    """Launch the agent and report what the guest sees; the caller waits for the port.

    Never trusts the pidfile: a live pid that is not serving the port is signalled away first, because
    `start_agent.sh` exits early while that pid lives and this endpoint would otherwise be a silent
    no-op. Returns after a short confirmation window instead of blocking the control API for minutes -
    under emulation the gateway can take that long to bind, and /health has to stay answerable.
    """
    if not Path(HERMES_BIN).exists():
        raise HTTPException(status_code=500, detail="Hermes is not installed in the guest")
    try:
        AGENT_HOLD.unlink()
    except OSError:
        pass
    if port_open(AGENT_PORT):
        return {"ok": True, "started": False, "portOpen": True}
    await asyncio.to_thread(clear_stale_agent)
    output = await asyncio.to_thread(launch_agent)
    opened = await asyncio.to_thread(wait_port_open, 15)
    note("start: port %s after the launch" % ("open" if opened else "still closed"))
    return {"ok": True, "started": True, "portOpen": opened,
            "log": tail(AGENT_LOG, 20), "output": output.strip()[:400]}


@app.post("/agent/stop", dependencies=[Depends(require_auth)])
async def agent_stop() -> dict:
    """Stop the agent for real: terminate, escalate, and wait for the port to go dark.

    A signal is not a stop - the gateway keeps answering for a while as it shuts down, and a client
    that reads that still-open port as readiness reports a successful restart for a process that then
    disappears. The hold file keeps the supervisor from undoing a deliberate stop.
    """
    try:
        AGENT_HOLD.parent.mkdir(parents=True, exist_ok=True)
        AGENT_HOLD.write_text("stopped by the device at %s\n" % time.strftime("%Y-%m-%d %H:%M:%S"))
    except OSError:
        pass
    pid = agent_pid()
    if pid:
        signal_agent(15)
    closed = await asyncio.to_thread(wait_port_closed, 20)
    if not closed and pid:
        signal_agent(9)
        closed = await asyncio.to_thread(wait_port_closed, 20)
    try:
        AGENT_PID.unlink()
    except OSError:
        pass
    note("stop: pid %s, port %s" % (pid or "?", "closed" if closed else "still open"))
    return {"ok": closed, "running": port_open(AGENT_PORT), "portClosed": closed}


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


async def agent_supervisor() -> None:
    """Bring the gateway back when it dies on its own.

    Nothing else in the guest restarts it, so a gateway that exits - a rejected key, a crash, an OOM
    kill - leaves the port dark until the app happens to ask again. A deliberate stop is respected
    through the hold file, and the attempts are capped so a guest that cannot start its gateway does
    not spin.

    The one thing this must never do is kill a gateway that is merely still starting: under emulation
    binding the port takes minutes, and a retry loop that "cleans up" a live-but-slow process every
    20 s is self-defeating - it restarts the startup clock forever. Hence: a live pid is left alone
    until it has had its whole grace period, and a launch that is younger than that grace period is
    never followed by another one.
    """
    attempts: list[float] = []
    last_launch = 0.0
    while True:
        await asyncio.sleep(20)
        try:
            if AGENT_HOLD.exists() or port_open(AGENT_PORT):
                continue
            if not Path(HERMES_BIN).exists():
                continue
            pid = agent_pid()
            fresh_launch = time.time() - last_launch < START_GRACE_SECONDS
            if pid_alive(pid) and fresh_launch:
                continue  # starting, not dead: leave it the minutes it needs
            if pid_alive(pid):
                note("supervisor: pid %s has served nothing since the last start - replacing it" % pid)
            elif pid:
                note("supervisor: pid %s is gone" % pid)
            attempts = [when for when in attempts if time.time() - when < 1800]
            if len(attempts) >= MAX_ATTEMPTS and not fresh_launch:
                continue
            attempts.append(time.time())
            last_launch = time.time()
            note("supervisor: the agent's port is dark - starting it (%d attempt(s) in 30 min)"
                 % len(attempts))
            await asyncio.to_thread(clear_stale_agent)
            await asyncio.to_thread(launch_agent)
        except Exception as exc:  # a supervisor that dies stops supervising
            note("supervisor: %s" % exc)


@app.on_event("startup")
async def start_supervisor() -> None:
    asyncio.create_task(agent_supervisor())


if __name__ == "__main__":
    import uvicorn

    # 0.0.0.0 on purpose: SLIRP forwards device traffic to the guest's eth0, not to its loopback.
    uvicorn.run(app, host="0.0.0.0", port=int(os.environ.get("CONTROL_PORT", "7080")),
                log_level="info")
