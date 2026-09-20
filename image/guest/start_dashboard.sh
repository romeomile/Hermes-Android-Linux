#!/bin/sh
# Start the Hermes dashboard inside the guest. Idempotent - safe to call on every boot.
set -u

HERMES_HOME_DIR=/root/.hermes
export HERMES_HOME="$HERMES_HOME_DIR"

# Loopback ONLY. On a non-loopback bind the dashboard always demands an auth
# provider and redirects / to /login; on loopback it serves the SPA with its
# session token injected. The relay (/bootstrap/relay.py, 0.0.0.0:9129) is what
# exposes this to the app - it forwards to 127.0.0.1:$DASHBOARD_PORT.
DASHBOARD_HOST=127.0.0.1
DASHBOARD_PORT=9128

# Resolve the launcher instead of assuming a path: pip installs console scripts into the
# distribution's bin directory, which differs between distros.
HERMES_BIN=$(command -v hermes 2>/dev/null || echo /usr/bin/hermes)
[ -x "$HERMES_BIN" ] || HERMES_BIN=/usr/local/bin/hermes
LOG=/var/log/hermes-dashboard.log
PIDFILE=/var/run/hermes-dashboard.pid

if [ ! -x "$HERMES_BIN" ]; then
    echo "ERROR: no hermes launcher found (looked in PATH and /usr/bin)" >&2
    exit 1
fi

if [ -f "$PIDFILE" ] && kill -0 "$(cat "$PIDFILE")" 2>/dev/null; then
    echo "dashboard already running (pid $(cat "$PIDFILE"))"
    exit 0
fi

mkdir -p /var/log /var/run "$HERMES_HOME_DIR"
: > "$LOG"

# The device token (api_token= on the kernel command line, handed down as API_TOKEN) becomes
# the dashboard's own session token: the dashboard honours HERMES_DASHBOARD_SESSION_TOKEN in
# place of its random per-start token, so the app can authenticate with the same bearer token
# it already holds for the control API. Without a token the dashboard mints its own.
if [ -n "${API_TOKEN:-}" ]; then
    export HERMES_DASHBOARD_SESSION_TOKEN="$API_TOKEN"
fi

# The dashboard's Chat tab spawns `hermes --tui`, which starts its own Python gateway
# (`python -m tui_gateway.entry`) and waits for it. Upstream's budget for that is 15s, sized for a
# native machine; under emulation the gateway needs far longer, and the tab then sits on
# "gateway startup timeout" instead of the chat. The spawned TUI inherits this environment, so the
# budget is set here. RPC calls get the same treatment: a slow guest must not time out mid-turn.
export HERMES_TUI_STARTUP_TIMEOUT_MS="${HERMES_TUI_STARTUP_TIMEOUT_MS:-600000}"
export HERMES_TUI_RPC_TIMEOUT_MS="${HERMES_TUI_RPC_TIMEOUT_MS:-600000}"

# --skip-build: the SPA was built at image-build time and is baked into the package's
# web_dist. --no-open: there is no browser in the guest.
nohup "$HERMES_BIN" dashboard --host "$DASHBOARD_HOST" --port "$DASHBOARD_PORT" \
    --no-open --skip-build >>"$LOG" 2>&1 &
echo $! > "$PIDFILE"
echo "dashboard started (pid $(cat "$PIDFILE")) on $DASHBOARD_HOST:$DASHBOARD_PORT"
