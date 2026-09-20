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

api = cfg.get("api_server") if isinstance(cfg.get("api_server"), dict) else {}
api.update({"enabled": True, "host": "0.0.0.0", "port": 8642})
cfg["api_server"] = api
cfg_path.write_text(yaml.safe_dump(cfg, sort_keys=False))

env_path = home / ".env"
lines = env_path.read_text().splitlines() if env_path.exists() else []
out, seen = [], False
for line in lines:
    if line.startswith("API_SERVER_KEY="):
        out.append("API_SERVER_KEY=%s" % token)
        seen = True
    else:
        out.append(line)
if not seen:
    out.append("API_SERVER_KEY=%s" % token)
env_path.write_text("\n".join(out).strip() + "\n")
env_path.chmod(0o600)
print("agent config prepared")
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

waited=0
while [ "$waited" -lt 240 ]; do
    if wget -q --header="Authorization: Bearer $TOKEN" -O- http://127.0.0.1:8642/v1/models >/dev/null 2>&1; then
        echo "[ready] agent"
        break
    fi
    waited=$((waited + 1))
    sleep 1
done

echo "=== bootstrap complete ==="
