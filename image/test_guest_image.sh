#!/bin/sh
# Boot the built guest image on the HOST under QEMU and check that the guest really comes up:
# boot, services, and the dashboard end-to-end through the in-guest relay.
#
# This is the only way to verify the image without a phone. It runs on an ordinary Linux host
# (x86_64 is fine - QEMU emulates the guest CPU); expect a slow boot, around a few minutes.
#
# Surface under test (all ports are the guest's own; the host uses high ports for the forwards):
#   7080  control API (0.0.0.0, bearer token)      host 17080
#   9128  Hermes dashboard (127.0.0.1, loopback)   reachable only through the relay
#   9129  relay (0.0.0.0 -> 127.0.0.1:9128)        host 19129, this is what the app connects to
#
# Usage: image/test_guest_image.sh [path/to/base.qcow2]
set -eu

REPO_DIR="${REPO_DIR:-$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)}"
ASSETS="$REPO_DIR/app/src/main/assets/vm"
WORK_DIR="${WORK_DIR:-$REPO_DIR/.image-build}"
IMAGE="${1:-$WORK_DIR/base.qcow2}"
TOKEN="TEST-TOKEN-NOT-A-SECRET"
CONTROL_PORT=17080
RELAY_PORT=19129
GUEST_RELAY_PORT=9129
GUEST_DASHBOARD_PORT=9128

SERIAL=""
[ -f "$IMAGE" ] || { echo "error: no guest image at $IMAGE (build it first)" >&2; exit 1; }
command -v qemu-system-aarch64 >/dev/null || { echo "error: install qemu-system-arm" >&2; exit 1; }

# Boot a copy: the run writes to the disk (the agent creates its own state).
cp -f "$IMAGE" "$WORK_DIR/boottest.qcow2"
rm -f "$WORK_DIR/boottest-serial.log"
SERIAL="$WORK_DIR/boottest-serial.log"

fail() {
    echo >&2
    echo "FAIL: $1" >&2
    if [ -n "$SERIAL" ] && [ -f "$SERIAL" ]; then
        echo "--- serial log (tail) ---" >&2
        tail -80 "$SERIAL" >&2
    fi
    exit 1
}

# Run a command inside the guest through the control API and print its stdout.
vm_exec() {
    curl -s -X POST "http://127.0.0.1:$CONTROL_PORT/vm/exec" \
        -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
        -d "$(python3 -c 'import json,sys; print(json.dumps({"cmd": sys.argv[1], "timeout": int(sys.argv[2])}))' "$1" "${2:-120}")"
}

echo "booting the guest (serial log: $SERIAL)"
qemu-system-aarch64 \
    -machine virt -cpu cortex-a53 -smp 2 -m 2048 \
    -drive if=none,file="$WORK_DIR/boottest.qcow2",id=base,format=qcow2 \
    -device virtio-blk-pci,drive=base \
    -netdev "user,id=net0,hostfwd=tcp::$CONTROL_PORT-:7080,hostfwd=tcp::$RELAY_PORT-:$GUEST_RELAY_PORT" \
    -device virtio-net-pci,netdev=net0,romfile= \
    -display none -serial "file:$SERIAL" \
    -kernel "$ASSETS/vmlinuz-virt" -initrd "$ASSETS/initramfs-virt" \
    -append "console=ttyAMA0 root=/dev/vda rootfstype=ext4 rootflags=rw modules=virtio_blk,ext4 api_token=$TOKEN quiet" \
    -pidfile "$WORK_DIR/boottest.pid" -daemonize

cleanup() { [ -f "$WORK_DIR/boottest.pid" ] && kill "$(cat "$WORK_DIR/boottest.pid")" 2>/dev/null || true; }
trap cleanup EXIT INT TERM

echo "waiting for the guest control API on 127.0.0.1:$CONTROL_PORT"
waited=0
until curl -sf "http://127.0.0.1:$CONTROL_PORT/health" >/dev/null 2>&1; do
    waited=$((waited + 1))
    if [ "$waited" -ge 600 ]; then fail "control API never answered"; fi
    sleep 1
done
echo "control API up after ${waited}s"
curl -s "http://127.0.0.1:$CONTROL_PORT/health"; echo

# The control API binds long before the dashboard (the dashboard imports the whole agent
# stack under emulation). Wait for the guest's OWN readiness lines: the guest bootstrap
# probes 7080/9128/9129 at port level and prints [ready] as each service accepts.
echo
echo "waiting for the guest bootstrap to report the dashboard and the relay ready"
waited=0
until grep -q '^\[ready\] dashboard' "$SERIAL" 2>/dev/null \
    && grep -q '^\[ready\] relay' "$SERIAL" 2>/dev/null; do
    if grep -q '^\[FAILED\]' "$SERIAL" 2>/dev/null; then
        echo "--- serial log (tail) ---" >&2
        tail -40 "$SERIAL" >&2
        fail "the guest bootstrap reported a failed service"
    fi
    waited=$((waited + 1))
    if [ "$waited" -ge 420 ]; then
        echo "--- serial log (tail) ---" >&2
        tail -40 "$SERIAL" >&2
        fail "dashboard/relay never became ready inside the guest (the guest waits up to 300s)"
    fi
    sleep 1
done
grep -E '^\[ready\] (dashboard|relay)' "$SERIAL" | tr -d '\r'
echo "dashboard + relay ready after ${waited}s"

echo
echo "guest toolchain:"
vm_exec 'cat /etc/alpine-release; python3 -V; hermes --version; command -v hermes; apk --version; rg --version | head -1' 300
echo

echo "guest listeners (netstat):"
vm_exec "netstat -ltn | grep -E ':(7080|$GUEST_DASHBOARD_PORT|$GUEST_RELAY_PORT)'" 120
echo

echo "control API agent status (now reports the dashboard):"
curl -s "http://127.0.0.1:$CONTROL_PORT/agent/status" -H "Authorization: Bearer $TOKEN"; echo

echo
echo "== dashboard through the relay (host 127.0.0.1:$RELAY_PORT -> guest $GUEST_RELAY_PORT) =="
echo "bootstrap readiness lines from the serial console:"
grep -E '^\[(ready|FAILED)\]' "$SERIAL" || true
echo

# 1. GET / must be the SPA, with the session token injected and no login required.
HTML_STATUS=$(curl -s -o "$WORK_DIR/boottest-index.html" -w '%{http_code}' \
    "http://127.0.0.1:$RELAY_PORT/")
echo "GET / -> HTTP $HTML_STATUS  ($(wc -c < "$WORK_DIR/boottest-index.html") bytes)"
[ "$HTML_STATUS" = "200" ] || fail "GET / through the relay returned HTTP $HTML_STATUS (expected 200)"
grep -q '__HERMES_SESSION_TOKEN__="' "$WORK_DIR/boottest-index.html" \
    || fail "GET / body has no __HERMES_SESSION_TOKEN__=\"...\" injection"
grep -q '__HERMES_AUTH_REQUIRED__=false' "$WORK_DIR/boottest-index.html" \
    || fail "GET / body does not say __HERMES_AUTH_REQUIRED__=false"
echo "GET / contains __HERMES_SESSION_TOKEN__=\" and __HERMES_AUTH_REQUIRED__=false"
SESSION_TOKEN=$(sed -n 's/.*__HERMES_SESSION_TOKEN__="\([^"]*\)".*/\1/p' "$WORK_DIR/boottest-index.html" | head -1)
echo "dashboard session token: ${SESSION_TOKEN:-<none>}"
if [ "$SESSION_TOKEN" = "$TOKEN" ]; then
    echo "session token equals the device token (HERMES_DASHBOARD_SESSION_TOKEN honoured)"
fi
[ -n "$SESSION_TOKEN" ] || SESSION_TOKEN="$TOKEN"

# The app talks to http://127.0.0.1:9129 (device) -> guest 9129, so its request carries that Host.
APP_HOST_STATUS=$(curl -s -o /dev/null -w '%{http_code}' -H "Host: 127.0.0.1:$GUEST_RELAY_PORT" \
    "http://127.0.0.1:$RELAY_PORT/")
echo "GET / with the app's Host header (127.0.0.1:$GUEST_RELAY_PORT) -> HTTP $APP_HOST_STATUS"

# 2. /api/status must answer 200 with JSON.
STATUS_CODE=$(curl -s -o "$WORK_DIR/boottest-status.json" -w '%{http_code}' \
    "http://127.0.0.1:$RELAY_PORT/api/status")
echo "/api/status -> HTTP $STATUS_CODE"
[ "$STATUS_CODE" = "200" ] || fail "/api/status returned HTTP $STATUS_CODE (expected 200)"
python3 -c 'import json,sys; json.load(open(sys.argv[1]))' "$WORK_DIR/boottest-status.json" \
    || fail "/api/status body is not valid JSON"
echo "/api/status body is valid JSON: $(head -c 300 "$WORK_DIR/boottest-status.json")"

# 3. RAW WebSocket handshake over a plain socket (no WS client library involved): the relay
#    must pass the Upgrade through untouched and the dashboard must answer 101.
echo "raw WebSocket handshake GET /api/ws?token=... (Upgrade: websocket, Origin: http://127.0.0.1:$GUEST_RELAY_PORT)"
python3 - "$RELAY_PORT" "$SESSION_TOKEN" "$GUEST_RELAY_PORT" <<'PYEOF' || fail "raw WebSocket handshake did not answer 101"
import base64, os, socket, sys

port, token, guest_port = int(sys.argv[1]), sys.argv[2], sys.argv[3]
request = (
    "GET /api/ws?token=%s HTTP/1.1\r\n"
    "Host: 127.0.0.1:%s\r\n"
    "Upgrade: websocket\r\n"
    "Connection: Upgrade\r\n"
    "Sec-WebSocket-Key: %s\r\n"
    "Sec-WebSocket-Version: 13\r\n"
    "Origin: http://127.0.0.1:%s\r\n"
    "\r\n"
) % (token, guest_port, base64.b64encode(os.urandom(16)).decode(), guest_port)

sock = socket.create_connection(("127.0.0.1", port), timeout=60)
sock.sendall(request.encode())
raw = sock.recv(4096).decode("latin1")
sock.close()
status = raw.splitlines()[0] if raw else "(no response)"
print(status)
for line in raw.splitlines()[1:]:
    if line.lower().startswith(("upgrade:", "sec-websocket-accept:")):
        print(line)
sys.exit(0 if status.startswith("HTTP/1.1 101") else 1)
PYEOF
echo "raw WebSocket handshake answered 101"

# 4. The dashboard must NOT be reachable on the guest's non-loopback address: SLIRP delivers
#    forwarded traffic to eth0, so a 0.0.0.0 bind here would expose the dashboard without the
#    relay (and force /login). Only the relay may listen there.
echo
echo "inside the guest - is the dashboard reachable on eth0 (it must NOT be)?"
LOOPBACK_PROBE=$(vm_exec "python3 -c \"import socket; s=socket.socket(); s.settimeout(3); print('connect_ex 10.0.2.15:$GUEST_DASHBOARD_PORT ->', s.connect_ex(('10.0.2.15', $GUEST_DASHBOARD_PORT)))\"" 60)
echo "$LOOPBACK_PROBE"
case "$LOOPBACK_PROBE" in
    *"-> 0"*) fail "the dashboard answers on the guest's non-loopback address 10.0.2.15:$GUEST_DASHBOARD_PORT - it must stay loopback-only" ;;
esac
echo "dashboard is loopback-only (eth0:$GUEST_DASHBOARD_PORT refused)"
echo
vm_exec "python3 -c \"import socket; s=socket.socket(); s.settimeout(3); print('connect_ex 127.0.0.1:$GUEST_DASHBOARD_PORT ->', s.connect_ex(('127.0.0.1', $GUEST_DASHBOARD_PORT)))\"" 60
echo
RELAY_PROBE=$(vm_exec "python3 -c \"import socket; s=socket.socket(); s.settimeout(3); print('connect_ex 10.0.2.15:$GUEST_RELAY_PORT ->', s.connect_ex(('10.0.2.15', $GUEST_RELAY_PORT)))\"" 60)
echo "$RELAY_PROBE"
case "$RELAY_PROBE" in
    *"-> 0"*) echo "relay is reachable on eth0:$GUEST_RELAY_PORT (that is the only way in)" ;;
    *) fail "the relay does not accept on eth0:$GUEST_RELAY_PORT, so the app's forwarded port would answer nothing" ;;
esac

echo
echo "dashboard log (tail):"
vm_exec 'tail -15 /var/log/hermes-dashboard.log' 60
echo
echo "relay log (tail):"
vm_exec 'tail -10 /var/log/hermes-relay.log' 60
echo
echo "guest bootstrap (serial console, from the service):"
grep -E 'guest bootstrap|token received|starting the control API|starting the relay|dashboard started|\[ready\]|\[FAILED\]|bootstrap complete' "$SERIAL" | tail -20 || true

echo
echo "=== boot test passed ==="
