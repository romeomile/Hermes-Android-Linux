#!/bin/bash
# Guest-disk persistence and recovery test.
#
# Answers one question with evidence instead of prose: what actually survives when the engine is
# stopped, when Android kills the app, and when a release ships a new base image.
#
# It runs on a build host (no phone) and mirrors what the app does, so the phases match the app's
# code paths one for one:
#
#   app: VmManager.buildQemuCommand()          -> the QEMU command below
#   app: VmManager.createUserImage()           -> qemu-img create -b base.qcow2 -F qcow2 user.qcow2
#   app: VmManager.start(), assetsReady()==false (new base image)
#        -> rename user.qcow2 to user.qcow2.previous, create a fresh overlay
#   app: VmManager.stop() -> destroy(), then destroyForcibly()  -> SIGTERM, then SIGKILL
#   Android force-stop                                    -> SIGKILL with no chance to flush
#
# Phases and what each one must show:
#   1  fresh overlay, write canary A + B, graceful stop
#   2  same overlay  -> A and B present (normal stop/start); write C; SIGKILL
#   3  same overlay  -> A, B and C present (dirty shutdown)
#   4  "base-image update": previous disk set aside, fresh overlay
#                    -> A, B, C NOT visible in the guest, user.qcow2.previous still on disk
#   5  recovery: previous overlay paired with the base image of its own release
#                    -> A, B and C present again
#
# Requires: qemu-system-aarch64, qemu-img, curl, python3, and a base image built by
# image/build_guest_image.sh. Placeholder token only - never a real key.
set -u

REPO_DIR=${REPO_DIR:-$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)}
ASSETS=$REPO_DIR/app/src/main/assets/vm
WORK_DIR=${WORK_DIR:-$REPO_DIR/.image-build/persistence-test}
QEMU_BIN=${QEMU_BIN:-qemu-system-aarch64}
PORT=${PORT:-27080}
TOKEN=${TOKEN:-testtoken123456}
RAM_MB=${RAM_MB:-1536}
CPUS=${CPUS:-2}
DISK_GB=${DISK_GB:-20}
BOOT_TIMEOUT=${BOOT_TIMEOUT:-1200}

RESULTS=$WORK_DIR/results.log
FAILED=0

say()  { printf '\n=== %s ===\n' "$*" | tee -a "$RESULTS"; }
note() { printf '%s\n' "$*" | tee -a "$RESULTS"; }
check() { # check <label> <expected> <actual>
    if [ "$2" = "$3" ]; then note "  PASS  $1 (expected: $2)"
    else note "  FAIL  $1 (expected: $2, actual: $3)"; FAILED=$((FAILED+1)); fi
}

for tool in "$QEMU_BIN" qemu-img curl python3; do
    command -v "$tool" >/dev/null || { echo "error: $tool not found" >&2; exit 1; }
done

# A guest from an earlier experiment that still holds the port answers /health just like the one we
# start, and silently receives every canary: assert the port is free and, after a boot, that the
# guest answering is one we just started (low uptime).
if ss -ltn 2>/dev/null | grep -q ":$PORT "; then
    echo "error: port $PORT is already in use - stop that guest first (ss -ltnp | grep $PORT)" >&2
    exit 1
fi

rm -rf "$WORK_DIR"; mkdir -p "$WORK_DIR"; : > "$RESULTS"

if [ -f "$ASSETS/base.qcow2.gz" ]; then
    gunzip -c "$ASSETS/base.qcow2.gz" > "$WORK_DIR/base.qcow2"
elif [ -f "$ASSETS/base.qcow2" ]; then
    cp "$ASSETS/base.qcow2" "$WORK_DIR/base.qcow2"
else
    echo "error: no guest image in $ASSETS - run image/build_guest_image.sh first" >&2
    exit 1
fi
cp "$ASSETS/vmlinuz-virt" "$ASSETS/initramfs-virt" "$WORK_DIR/"

say "persistence test"
note "base image sha256: $(sha256sum "$WORK_DIR/base.qcow2" | cut -d' ' -f1)"
note "the app's extraction marker is derived from the packed .gz, so a new image always means a new overlay"

boot() { # boot <tag> <overlay>
    local tag=$1 img=$2
    setsid "$QEMU_BIN" -machine virt -cpu cortex-a53 -smp "$CPUS" -m "$RAM_MB" \
        -drive if=none,file="$WORK_DIR/base.qcow2",id=base,format=qcow2,readonly=on \
        -drive if=none,file="$img",id=user,format=qcow2 \
        -device virtio-blk-pci,drive=user \
        -netdev user,id=net0,hostfwd=tcp::"$PORT"-:7080 \
        -device virtio-net-pci,netdev=net0,romfile= \
        -fw_cfg name=opt/api_token,string="$TOKEN" \
        -display none -serial file:"$WORK_DIR/qemu-$tag.log" \
        -kernel "$WORK_DIR/vmlinuz-virt" -initrd "$WORK_DIR/initramfs-virt" \
        -append "console=ttyAMA0 root=/dev/vda rootfstype=ext4 rootflags=rw modules=virtio_blk,ext4 api_token=$TOKEN quiet" \
        >/dev/null 2>&1 &
    echo $! > "$WORK_DIR/qemu.pid"
    sleep 2
    kill -0 "$(cat "$WORK_DIR/qemu.pid")" 2>/dev/null || { note "boot[$tag]: QEMU exited immediately"; return 1; }
    note "boot[$tag]: overlay $(basename "$img") ($(stat -c%s "$img") bytes)"
}

wait_for_guest() { # wait until the guest control API answers, and prove it is ours
    local waited=0 code uptime
    while [ "$waited" -lt "$BOOT_TIMEOUT" ]; do
        code=$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$PORT/health" 2>/dev/null || true)
        [ "$code" = "200" ] && break
        kill -0 "$(cat "$WORK_DIR/qemu.pid")" 2>/dev/null || { note "  QEMU exited while waiting"; return 1; }
        sleep 5; waited=$((waited+5))
    done
    [ "$code" = "200" ] || { note "  TIMEOUT after ${BOOT_TIMEOUT}s"; return 1; }
    uptime=$(guest 'cut -d" " -f1 /proc/uptime')
    note "  control API up after ~${waited}s, guest uptime ${uptime}s"
    case "$uptime" in ''|*[!0-9.]*) note "  GUARD: could not read uptime, not our guest"; return 1;; esac
    awk -v u="$uptime" 'BEGIN{ if (u+0 > 600) exit 1 }' || {
        note "  GUARD: that guest has been up ${uptime}s - wrong guest on this port"; return 1; }
    return 0
}

guest() { # run a command inside the guest through the control API
    curl -s -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
        -d "$(python3 -c 'import json,sys; print(json.dumps({"cmd": sys.argv[1], "timeout": 60}))' "$1")" \
        "http://127.0.0.1:$PORT/vm/exec" 2>/dev/null | python3 -c 'import json,sys
try: print(json.load(sys.stdin).get("stdout","").strip())
except Exception: print("")'
}

stop_soft() { # what the app's stop button does
    local pid; pid=$(cat "$WORK_DIR/qemu.pid")
    kill -TERM "$pid" 2>/dev/null
    for i in $(seq 1 15); do kill -0 "$pid" 2>/dev/null || { note "  stopped on SIGTERM"; sleep 2; return 0; }; sleep 1; done
    kill -KILL "$pid" 2>/dev/null; sleep 2; note "  SIGTERM ignored, SIGKILL used (the app does the same)"
}

stop_hard() { kill -KILL "$(cat "$WORK_DIR/qemu.pid")" 2>/dev/null; sleep 2; note "  SIGKILL (Android force-stop)"; }

canary() { guest "cat /root/canary-$1.txt 2>/dev/null || echo MISSING"; }

say "phase 1 - fresh overlay (a new install)"
qemu-img create -f qcow2 -b "$WORK_DIR/base.qcow2" -F qcow2 "$WORK_DIR/user.qcow2" "${DISK_GB}G" >/dev/null
if boot 1 "$WORK_DIR/user.qcow2" && wait_for_guest; then
    note "  writing canary A + B and syncing: $(guest 'echo canary-A > /root/canary-a.txt; echo canary-B > /root/canary-b.txt; sync; echo ok')"
    stop_soft
else FAILED=$((FAILED+1)); fi

say "phase 2 - same overlay, normal stop/start"
if boot 2 "$WORK_DIR/user.qcow2" && wait_for_guest; then
    check "canary A survives a normal stop/start" "canary-A" "$(canary a)"
    check "canary B survives a normal stop/start" "canary-B" "$(canary b)"
    note "  writing canary C, then killing QEMU outright"
    guest 'echo canary-C > /root/canary-c.txt; sync; echo ok' >/dev/null
    stop_hard
else FAILED=$((FAILED+1)); fi

say "phase 3 - after an Android-style force-stop"
if boot 3 "$WORK_DIR/user.qcow2" && wait_for_guest; then
    check "canary A survives a force-stop" "canary-A" "$(canary a)"
    check "canary B survives a force-stop" "canary-B" "$(canary b)"
    check "canary C survives a force-stop" "canary-C" "$(canary c)"
    note "  ext4 journal recovery seen by the guest: $(grep -c 'recovery complete' "$WORK_DIR/qemu-3.log" 2>/dev/null || echo 0)"
    stop_soft
else FAILED=$((FAILED+1)); fi

say "phase 4 - a release with a new base image (what the app does with the old overlay)"
mv "$WORK_DIR/user.qcow2" "$WORK_DIR/user.qcow2.previous"
note "  previous disk kept as user.qcow2.previous ($(stat -c%s "$WORK_DIR/user.qcow2.previous") bytes)"
qemu-img create -f qcow2 -b "$WORK_DIR/base.qcow2" -F qcow2 "$WORK_DIR/user.qcow2" "${DISK_GB}G" >/dev/null
if boot 4 "$WORK_DIR/user.qcow2" && wait_for_guest; then
    check "a new base image starts from an empty guest (canary A)" "MISSING" "$(canary a)"
    check "a new base image starts from an empty guest (canary C)" "MISSING" "$(canary c)"
    check "the previous disk is still on storage" "yes" "$([ -s "$WORK_DIR/user.qcow2.previous" ] && echo yes || echo no)"
    stop_soft
else FAILED=$((FAILED+1)); fi

say "phase 5 - recovery: previous overlay with the base image of its own release"
cp "$WORK_DIR/user.qcow2.previous" "$WORK_DIR/user.qcow2.restored"
if boot 5 "$WORK_DIR/user.qcow2.restored" && wait_for_guest; then
    check "canary A is back after the restore" "canary-A" "$(canary a)"
    check "canary B is back after the restore" "canary-B" "$(canary b)"
    check "canary C is back after the restore" "canary-C" "$(canary c)"
    stop_soft
else FAILED=$((FAILED+1)); fi

say "result"
if [ "$FAILED" -eq 0 ]; then
    note "ALL CHECKS PASSED - normal stop/start and force-stop preserve the guest;"
    note "an image update starts an empty guest and keeps the previous disk aside; the restore path works."
else
    note "$FAILED CHECK(S) FAILED - see the FAIL lines above"
fi
note "artifacts: $WORK_DIR (results.log, qemu-*.log, the overlays)"
exit "$([ "$FAILED" -eq 0 ] && echo 0 || echo 1)"
