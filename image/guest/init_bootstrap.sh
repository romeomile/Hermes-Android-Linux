#!/bin/sh
# Guest bootstrap: runs on every boot (OpenRC, after networking).
#
# 1. Reads the device-generated token from the kernel command line.
# 2. Keeps the agent's config sane (HERMES_HOME=/root/.hermes, .env mode 600) and drops the
#    obsolete api_server settings older images wrote - that adapter is gone: the agent's
#    dashboard, reached through a loopback relay, is the app's UI surface now.
# 3. Starts the control API (0.0.0.0:7080), the dashboard (127.0.0.1:9128) and the relay
#    (0.0.0.0:9129, the only way in from eth0), then waits for all three.
set -u

DASHBOARD_PORT=9128
RELAY_PORT=9129
CONTROL_PORT=7080

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

# Config hygiene only. Nothing host- or install-specific is written here: the token lives on
# the device (kernel cmdline / /bootstrap/token, mode 600) and is handed to the processes that
# need it. The control API uses it as its bearer token; start_dashboard.sh passes it as
# HERMES_DASHBOARD_SESSION_TOKEN, which the dashboard honours in place of its random
# per-start session token, so the app can talk to the dashboard API with the same token.
[ -f "$HERMES_HOME/config.yaml" ] || printf '{}\n' > "$HERMES_HOME/config.yaml"
[ -f "$HERMES_HOME/.env" ] || : > "$HERMES_HOME/.env"
chmod 600 "$HERMES_HOME/.env"

# Drop the api_server settings earlier images wrote (platforms.api_server.*, api_server.* in
# config.yaml and API_SERVER_* in .env). The api_server adapter is no longer started - the
# dashboard supersedes it for the app UI - so leaving those keys behind would only describe a
# service that never runs. Merge-only: every other key is preserved, and a failure here never
# blocks boot.
python3 - "$HERMES_HOME" <<'PYEOF' || true
import sys
from pathlib import Path

home = Path(sys.argv[1])
cfg_path = home / "config.yaml"
if cfg_path.exists():
    try:
        import yaml

        cfg = yaml.safe_load(cfg_path.read_text()) or {}
        changed = False
        if isinstance(cfg.get("api_server"), dict):
            del cfg["api_server"]
            changed = True
        platforms = cfg.get("platforms")
        if isinstance(platforms, dict) and isinstance(platforms.get("api_server"), dict):
            del platforms["api_server"]
            changed = True
            if not platforms:
                del cfg["platforms"]
        # No lazy feature installs inside the guest. A feature the image does not ship otherwise
        # tries `pip install` at first use and waits out its whole timeout before giving up: the
        # speech-to-text ladder did exactly that during startup, holding the dashboard's chat on
        # "summoning hermes" for minutes and then failing. Everything the image ships is installed
        # at build time, and anything the user wants on top they install themselves.
        security = cfg.get("security")
        if not isinstance(security, dict):
            security = {}
            cfg["security"] = security
        if security.get("allow_lazy_installs") is not False:
            security["allow_lazy_installs"] = False
            changed = True
        if changed:
            cfg_path.write_text(yaml.safe_dump(cfg, sort_keys=False))
            print("normalised config.yaml (dropped obsolete api_server settings, lazy installs off)")
    except Exception as exc:
        print("WARNING: config.yaml left untouched (%s)" % exc)

env_path = home / ".env"
if env_path.exists():
    lines = env_path.read_text().splitlines()
    kept = [line for line in lines if not line.split("=", 1)[0].strip().startswith("API_SERVER_")]
    if len(kept) != len(lines):
        env_path.write_text(("\n".join(kept).strip() + "\n") if kept else "")
        print("removed obsolete API_SERVER_* entries from .env")
    env_path.chmod(0o600)
PYEOF

port_open() {
    # Port-level probe: readiness must not depend on an authenticated HTTP endpoint (the guest
    # may have no model configured yet).
    python3 -c "import socket,sys; s=socket.socket(); s.settimeout(1); sys.exit(0 if s.connect_ex(('127.0.0.1', $1))==0 else 1)" 2>/dev/null
}

wait_port() {  # wait_port <port> <label> <logfile> <seconds>
    waited=0
    while [ "$waited" -lt "$4" ]; do
        if port_open "$1"; then
            echo "[ready] $2 (port $1 after ${waited}s)"
            return 0
        fi
        waited=$((waited + 1))
        sleep 1
    done
    echo "[FAILED] $2 did not answer on port $1 after $4s; last log lines:"
    tail -30 "$3" 2>/dev/null || echo "(no log at $3)"
    return 1
}

# Grow the root filesystem into the disk the app attached. The app creates the writable overlay at
# a size the user chooses (20 GB by default) and QEMU presents it as /dev/vda; the filesystem
# inside is a fixed-size ext4, so this call is what turns the extra disk into usable space. An ext4
# grow works online (the root is mounted read-write here) and is a no-op once the filesystem fills
# the disk. resize2fs comes from Alpine's e2fsprogs-extra. Nothing here is fatal: if the tool is
# missing or the resize fails, the guest keeps the filesystem its base image shipped with, which is
# already larger than the 5 GB the app promises.
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

echo "starting the control API on 0.0.0.0:$CONTROL_PORT"
API_TOKEN="$TOKEN" nohup /usr/bin/python3 /bootstrap/api_server.py >>/var/log/control-api.log 2>&1 &
echo $! > /var/run/control-api.pid

API_TOKEN="$TOKEN" sh /bootstrap/start_dashboard.sh


if [ -f /var/run/hermes-relay.pid ] && kill -0 "$(cat /var/run/hermes-relay.pid)" 2>/dev/null; then
    echo "relay already running (pid $(cat /var/run/hermes-relay.pid))"
else
    echo "starting the relay on 0.0.0.0:$RELAY_PORT -> 127.0.0.1:$DASHBOARD_PORT"
    nohup /usr/bin/python3 /bootstrap/relay.py \
        --listen-host 0.0.0.0 --listen-port "$RELAY_PORT" \
        --target-host 127.0.0.1 --target-port "$DASHBOARD_PORT" \
        >>/var/log/hermes-relay.log 2>&1 &
    echo $! > /var/run/hermes-relay.pid
fi

wait_port "$CONTROL_PORT" "control-api" /var/log/control-api.log 60
wait_port "$DASHBOARD_PORT" "dashboard" /var/log/hermes-dashboard.log 300
# The dashboard prints this once it has bound; surface it on the console for the app's log view.
grep -h 'HERMES_DASHBOARD_READY' /var/log/hermes-dashboard.log 2>/dev/null | tail -1 || true
wait_port "$RELAY_PORT" "relay" /var/log/hermes-relay.log 60

echo "=== bootstrap complete ==="
