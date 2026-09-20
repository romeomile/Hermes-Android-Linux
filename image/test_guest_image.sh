#!/bin/sh
# Boot the built guest image on the HOST under QEMU and check that the guest really comes up:
# boot, services, agent config, control API, and the agent's API server.
#
# This is the only way to verify the image without a phone. It runs on an ordinary Linux host
# (x86_64 is fine — QEMU emulates the guest CPU); expect a slow boot, around a few minutes.
#
# Usage: image/test_guest_image.sh [path/to/base.qcow2]
set -eu

REPO_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
ASSETS="$REPO_DIR/app/src/main/assets/vm"
WORK_DIR="${WORK_DIR:-$REPO_DIR/.image-build}"
IMAGE="${1:-$WORK_DIR/base.qcow2}"
TOKEN="TEST-TOKEN-NOT-A-SECRET"
CONTROL_PORT=17080
AGENT_PORT=18642

[ -f "$IMAGE" ] || { echo "error: no guest image at $IMAGE (build it first)" >&2; exit 1; }
command -v qemu-system-aarch64 >/dev/null || { echo "error: install qemu-system-arm" >&2; exit 1; }

# Boot a copy: the run writes to the disk (the agent creates its own state).
cp -f "$IMAGE" "$WORK_DIR/boottest.qcow2"
rm -f "$WORK_DIR/boottest-serial.log"

echo "booting the guest (serial log: $WORK_DIR/boottest-serial.log)"
qemu-system-aarch64 \
    -machine virt -cpu cortex-a53 -smp 2 -m 2048 \
    -drive if=none,file="$WORK_DIR/boottest.qcow2",id=base,format=qcow2 \
    -device virtio-blk-pci,drive=base \
    -netdev "user,id=net0,hostfwd=tcp::$CONTROL_PORT-:7080,hostfwd=tcp::$AGENT_PORT-:8642" \
    -device virtio-net-pci,netdev=net0,romfile= \
    -display none -serial "file:$WORK_DIR/boottest-serial.log" \
    -kernel "$ASSETS/vmlinuz-virt" -initrd "$ASSETS/initramfs-virt" \
    -append "console=ttyAMA0 root=/dev/vda rootfstype=ext4 rootflags=rw modules=virtio_blk,ext4 api_token=$TOKEN quiet" \
    -pidfile "$WORK_DIR/boottest.pid" -daemonize

cleanup() { [ -f "$WORK_DIR/boottest.pid" ] && kill "$(cat "$WORK_DIR/boottest.pid")" 2>/dev/null || true; }
trap cleanup EXIT INT TERM

echo "waiting for the guest control API on 127.0.0.1:$CONTROL_PORT"
waited=0
until curl -sf "http://127.0.0.1:$CONTROL_PORT/health" >/dev/null 2>&1; do
    waited=$((waited + 1))
    if [ "$waited" -ge 600 ]; then
        echo "FAIL: control API never answered" >&2
        tail -40 "$WORK_DIR/boottest-serial.log" >&2
        exit 1
    fi
    sleep 1
done
echo "control API up after ${waited}s"
curl -s "http://127.0.0.1:$CONTROL_PORT/health"; echo

echo
echo "guest toolchain:"
curl -s -X POST "http://127.0.0.1:$CONTROL_PORT/vm/exec" \
    -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
    -d '{"cmd":"cat /etc/alpine-release; python3 -V; hermes --version; apk --version; rg --version | head -1","timeout":300}' \
    | head -c 1200
echo

echo
echo "agent status:"
curl -s "http://127.0.0.1:$CONTROL_PORT/agent/status" -H "Authorization: Bearer $TOKEN"; echo

echo
echo "agent API server (port $AGENT_PORT):"
curl -s "http://127.0.0.1:$AGENT_PORT/v1/models" -H "Authorization: Bearer $TOKEN" | head -c 400; echo

echo
echo "inside the guest - what is listening and what does the API server answer:"
curl -s -X POST "http://127.0.0.1:$CONTROL_PORT/vm/exec" \
    -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
    -d "{\"cmd\":\"netstat -ltn | grep -E '8642|7080'; echo '--- /v1/models ---'; curl -sS -m 10 -o /dev/null -w 'HTTP %{http_code}\\n' -H 'Authorization: Bearer $TOKEN' http://127.0.0.1:8642/v1/models; curl -sS -m 10 -H 'Authorization: Bearer $TOKEN' http://127.0.0.1:8642/v1/models | head -c 300\",\"timeout\":120}" \
    | head -c 900; echo

echo
echo "agent log (tail):"
curl -s "http://127.0.0.1:$CONTROL_PORT/agent/log?lines=25" -H "Authorization: Bearer $TOKEN" | head -c 2000; echo
