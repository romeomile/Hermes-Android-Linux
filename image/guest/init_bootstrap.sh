#!/bin/sh
# Guest bootstrap: runs on every boot (OpenRC, after networking).
#
# 1. Reads the device-generated token from the kernel command line.
# 2. Ensures the agent's config enables the API server on 0.0.0.0:8642, and that
#    API_SERVER_KEY matches the token so the on-device frontend can reach it.
# 3. Starts the control API and the Hermes agent gateway.
set -u

echo "=== Hermes Android Linux guest bootstrap ==="

TOKEN=$(tr ' ' '\n' < /proc/cmdline | grep '^api_token=' | cut -d= -f2- || true)
if [ -n "${TOKEN:-}" ]; then
    echo -n "$TOKEN" > /bootstrap/token
    chmod 600 /bootstrap/token
    echo "token received from the kernel command line"
else
    TOKEN=$(cat /bootstrap/token 2>/dev/null || echo "")
    echo "WARNING: no api_token in cmdline, reusing the persisted token"
fi

export HERMES_HOME=/root/.hermes
mkdir -p "$HERMES_HOME"

# Keep config.yaml valid: make sure the api_server block is on 0.0.0.0:8642
# without clobbering anything else in the file, and keep API_SERVER_KEY in step
# with the device token.
python3 - "$TOKEN" <<'PYEOF'
import sys
from pathlib import Path

import yaml

token = sys.argv[1]
home = Path("/root/.hermes")
home.mkdir(parents=True, exist_ok=True)

cfg_path = home / "config.yaml"
cfg = {}
if cfg_path.exists():
    try:
        cfg = yaml.safe_load(cfg_path.read_text()) or {}
    except Exception as exc:
        print("WARNING: config.yaml unreadable (%s), writing the api_server block only" % exc)
        cfg = {}

# The API server adapter resolves its bind address from platforms.api_server.extra
# (host/port/key), falling back to the API_SERVER_* environment. Write both, so it binds
# 0.0.0.0 whatever path the running version prefers: SLIRP delivers forwarded traffic to the
# guest's eth0, never to its loopback.
platforms = cfg.get("platforms") if isinstance(cfg.get("platforms"), dict) else {}
api_platform = platforms.get("api_server") if isinstance(platforms.get("api_server"), dict) else {}
extra = api_platform.get("extra") if isinstance(api_platform.get("extra"), dict) else {}
extra.update({"host": "0.0.0.0", "port": 8642, "key": token})
api_platform["enabled"] = True
api_platform["extra"] = extra
platforms["api_server"] = api_platform
cfg["platforms"] = platforms

api = cfg.get("api_server") if isinstance(cfg.get("api_server"), dict) else {}
api.update({"enabled": True, "host": "0.0.0.0", "port": 8642})
cfg["api_server"] = api

# Nothing may be installed at runtime: the guest has no package source, and a lazy install stalls the
# agent for minutes before failing. Everything the guest needs is baked into this image.
security = cfg.get("security") if isinstance(cfg.get("security"), dict) else {}
security["allow_lazy_installs"] = False
cfg["security"] = security

cfg_path.write_text(yaml.safe_dump(cfg, sort_keys=False))

env_path = home / ".env"
wanted = {
    "API_SERVER_ENABLED": "true",
    "API_SERVER_HOST": "0.0.0.0",
    "API_SERVER_PORT": "8642",
    "API_SERVER_KEY": token,
}
lines = env_path.read_text().splitlines() if env_path.exists() else []
out = []
for line in lines:
    name = line.split("=", 1)[0].strip() if "=" in line else ""
    if name in wanted:
        out.append("%s=%s" % (name, wanted.pop(name)))
    else:
        out.append(line)
for name, value in wanted.items():
    out.append("%s=%s" % (name, value))
env_path.write_text("\n".join(out).strip() + "\n")
env_path.chmod(0o600)
print("agent config prepared (api_server on 0.0.0.0:8642)")
PYEOF

# Grow the root filesystem into the disk the app attached. The app creates the writable disk at a size
# the user chooses (20 GB by default) and QEMU presents it as /dev/vda; the filesystem inside is a
# fixed-size ext4, so this call is what turns the extra disk into usable space. An ext4 grow works
# online (the root is mounted read-write here) and is a no-op once the filesystem fills the disk.
# resize2fs comes from Alpine's e2fsprogs-extra. Nothing here is fatal: if the tool is missing or the
# resize fails, the guest keeps the filesystem its base image shipped with.
grow_rootfs() {
    if [ ! -b /dev/vda ]; then
        echo "WARNING: /dev/vda not found, leaving the filesystem as it is"
        return 0
    fi
    if ! command -v resize2fs >/dev/null 2>&1; then
        echo "WARNING: resize2fs is missing, leaving the filesystem as it is"
        return 0
    fi
    resize2fs /dev/vda 2>&1 | tail -2 || true
    TOTAL_MB=$(df -Pm / 2>/dev/null | awk 'NR==2 {print $2}')
    FREE_MB=$(df -Pm / 2>/dev/null | awk 'NR==2 {print $4}')
    if [ -n "${FREE_MB:-}" ]; then
        echo "[ready] root filesystem ${TOTAL_MB} MB, ${FREE_MB} MB free"
        if [ "${FREE_MB:-0}" -lt 5120 ]; then
            echo "[WARNING] under 5 GB free on /: the attached disk is smaller than the app's default"
        fi
    fi
}

grow_rootfs

echo "starting the control API on 0.0.0.0:7080"
API_TOKEN="$TOKEN" nohup /usr/bin/python3 /bootstrap/api_server.py >>/var/log/control-api.log 2>&1 &
echo $! > /var/run/control-api.pid

sh /bootstrap/start_agent.sh

waited=0
while [ "$waited" -lt 60 ]; do
    if wget -q -O- http://127.0.0.1:7080/health >/dev/null 2>&1; then
        echo "[ready] control-api"
        break
    fi
    waited=$((waited + 1))
    sleep 1
done
if [ "$waited" -ge 60 ]; then
    echo "[FAILED] control-api did not answer; last log lines:"
    tail -20 /var/log/control-api.log 2>/dev/null || echo "(no control-api log)"
fi

waited=0
# The agent takes minutes to become ready on this hardware: the gateway enumerates every tool
# check_fn, probes the environment and opens its databases before the api_server adapter binds 8642.
# Wait for the port rather than for a log line, report progress so the device log shows movement, and
# allow a long budget — giving up early is what makes a slow boot look like a broken one.
while [ "$waited" -lt 900 ]; do
    # Port-level probe: the agent may have no model configured yet, so readiness must not depend
    # on an authenticated HTTP endpoint answering with content.
    if python3 -c "import socket,sys; s=socket.socket(); s.settimeout(1); sys.exit(0 if s.connect_ex(('127.0.0.1',8642))==0 else 1)" 2>/dev/null; then
        echo "[ready] agent (port 8642 after ${waited}s)"
        break
    fi
    if [ $((waited % 30)) -eq 0 ] && [ "$waited" -gt 0 ]; then
        echo "[waiting] agent on port 8642 (${waited}s so far)"
    fi
    waited=$((waited + 1))
    sleep 1
done
if [ "$waited" -ge 900 ]; then
    echo "[FAILED] agent did not answer on port 8642 within 900s; last log lines:"
    tail -30 /var/log/hermes-agent.log 2>/dev/null || echo "(no agent log)"
fi

echo "=== bootstrap complete ==="
