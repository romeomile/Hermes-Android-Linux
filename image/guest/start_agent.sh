#!/bin/sh
# Start the Hermes agent inside the guest. Idempotent - safe to call on every boot.
set -u

HERMES_HOME_DIR=/root/.hermes
export HERMES_HOME="$HERMES_HOME_DIR"
# Resolve the launcher instead of assuming a path: pip installs console scripts into the
# distribution's bin directory, which differs between distros.
HERMES_BIN=$(command -v hermes 2>/dev/null || echo /usr/bin/hermes)
[ -x "$HERMES_BIN" ] || HERMES_BIN=/usr/local/bin/hermes
LOG=/var/log/hermes-agent.log
PIDFILE=/var/run/hermes-agent.pid

if [ ! -x "$HERMES_BIN" ]; then
    echo "ERROR: no hermes launcher found (looked in PATH and /usr/bin)" >&2
    exit 1
fi

if [ -f "$PIDFILE" ] && kill -0 "$(cat "$PIDFILE")" 2>/dev/null; then
    echo "agent already running (pid $(cat "$PIDFILE"))"
    exit 0
fi

mkdir -p /var/log /var/run "$HERMES_HOME_DIR"
: > "$LOG"

# The gateway runs in the foreground; detach it so boot can continue.
nohup "$HERMES_BIN" gateway run >>"$LOG" 2>&1 &
echo $! > "$PIDFILE"
echo "agent started (pid $(cat "$PIDFILE"))"
