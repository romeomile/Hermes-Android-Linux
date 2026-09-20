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
while [ "$waited" -lt 240 ]; do
    # Port-level probe: the agent may have no model configured yet, so readiness must not depend
    # on an authenticated HTTP endpoint answering with content.
    if python3 -c "import socket,sys; s=socket.socket(); s.settimeout(1); sys.exit(0 if s.connect_ex(('127.0.0.1',8642))==0 else 1)" 2>/dev/null; then
        echo "[ready] agent"
        break
    fi
    waited=$((waited + 1))
    sleep 1
done
if [ "$waited" -ge 240 ]; then
    echo "[FAILED] agent did not answer on port 8642; last log lines:"
    tail -30 /var/log/hermes-agent.log 2>/dev/null || echo "(no agent log)"
fi

echo "=== bootstrap complete ==="
