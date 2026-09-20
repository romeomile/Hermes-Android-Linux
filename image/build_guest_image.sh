#!/bin/sh
# Build the guest disk image (base.qcow2.gz) for Hermes Android Linux.
#
# The guest is a real Alpine Linux aarch64 root filesystem with Hermes Agent
# preinstalled, packed into a compressed qcow2 disk that the app extracts and
# QEMU boots.
#
# Runs on any Linux host (x86_64 included) - no ARM hardware, no cross-Docker.
# Requirements:
#   * root (the rootfs is populated through a chroot)
#   * e2fsprogs  (mke2fs -d populates the filesystem from a directory, no loop mount)
#   * qemu-utils (qemu-img convert)
#   * qemu-user-static with binfmt_misc registered for qemu-aarch64, so the
#     guest's own apk/pip run during the build:
#       apt-get install -y qemu-user-static binfmt-support
#       update-binfmts --enable qemu-aarch64
set -eu

REPO_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
GUEST_DIR="$REPO_DIR/image/guest"
WORK_DIR="${WORK_DIR:-$REPO_DIR/.image-build}"
OUT_DIR="$REPO_DIR/app/src/main/assets/vm"
OUT="$OUT_DIR/base.qcow2.gz"

ALPINE_BRANCH="v3.19"
ALPINE_VERSION="3.19.1"
MINIROOTFS_URL="https://dl-cdn.alpinelinux.org/alpine/${ALPINE_BRANCH}/releases/aarch64/alpine-minirootfs-${ALPINE_VERSION}-aarch64.tar.gz"
MINIROOTFS_SHA256="7ef5eef3a5b1d198dfb1610cde1ef5b0755ff5d838fb1e5e1b9f42b59214820f"

# A real Linux userspace: shells, package manager, python, the usual tools the
# agent's terminal tool expects, plus TLS certificates and timezone data.
PACKAGES="alpine-base openrc bash busybox-extras curl git ripgrep nano less procps \
coreutils findutils grep sed tar gzip xz bzip2 tzdata ca-certificates python3 py3-pip"

DISK_SIZE="${DISK_SIZE:-2G}"

say() { printf '\n=== %s ===\n' "$1"; }

# Run a command inside the guest with a CLEAN environment. The host shell may carry variables that
# silently break the guest: SSL_CERT_FILE (Hermes's own venv bundle) leaves OpenSSL with no trust
# anchors, and a leaked HERMES_HOME would send guest config to the wrong place.
guest_run() {
    env -i PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
        HOME=/root TERM=dumb LANG=C.UTF-8 chroot "$ROOTFS" "$@"
}

[ "$(id -u)" = "0" ] || { echo "error: run as root (chroot is used to populate the guest)" >&2; exit 1; }
command -v mke2fs >/dev/null || { echo "error: mke2fs not found (install e2fsprogs)" >&2; exit 1; }
command -v qemu-img >/dev/null || { echo "error: qemu-img not found (install qemu-utils)" >&2; exit 1; }
if [ ! -f /proc/sys/fs/binfmt_misc/qemu-aarch64 ]; then
    echo "error: aarch64 binfmt is not registered, the guest's own tools cannot run." >&2
    echo "       install qemu-user-static + binfmt-support, then: update-binfmts --enable qemu-aarch64" >&2
    exit 1
fi
for required in api_server.py init_bootstrap.sh start_agent.sh hermes-bootstrap.initd; do
    [ -f "$GUEST_DIR/$required" ] || { echo "error: missing $GUEST_DIR/$required" >&2; exit 1; }
done

mkdir -p "$WORK_DIR" "$OUT_DIR"
ROOTFS="$WORK_DIR/rootfs"
RAW="$WORK_DIR/base.raw"
QCOW="$WORK_DIR/base.qcow2"
TARBALL="$WORK_DIR/alpine-minirootfs.tar.gz"

say "1/9 Fetch Alpine ${ALPINE_VERSION} aarch64 minirootfs"
if [ -f "$TARBALL" ] && echo "$MINIROOTFS_SHA256  $TARBALL" | sha256sum -c - >/dev/null 2>&1; then
    echo "cached and verified: $TARBALL"
else
    curl -fsSL -o "$TARBALL" "$MINIROOTFS_URL"
    echo "$MINIROOTFS_SHA256  $TARBALL" | sha256sum -c - || { echo "error: checksum mismatch" >&2; exit 1; }
fi

say "2/9 Unpack the root filesystem"
rm -rf "$ROOTFS"
mkdir -p "$ROOTFS"
tar xzf "$TARBALL" -C "$ROOTFS"
cp /etc/resolv.conf "$ROOTFS/etc/resolv.conf"
cat > "$ROOTFS/etc/apk/repositories" <<REPOS
https://dl-cdn.alpinelinux.org/alpine/${ALPINE_BRANCH}/main
https://dl-cdn.alpinelinux.org/alpine/${ALPINE_BRANCH}/community
REPOS

say "3/9 Enter the guest (bind mounts for the chroot)"
cleanup() {
    for m in dev/pts dev proc sys; do
        umount "$ROOTFS/$m" 2>/dev/null || true
    done
}
trap cleanup EXIT INT TERM
mount --bind /dev "$ROOTFS/dev"
mount --bind /dev/pts "$ROOTFS/dev/pts"
mount -t proc proc "$ROOTFS/proc"
mount --bind /sys "$ROOTFS/sys"

say "4/9 Install guest packages with the guest's own apk"
# shellcheck disable=SC2086
guest_run /sbin/apk add --no-cache $PACKAGES

say "5/9 Install Hermes Agent inside the guest (musl/aarch64 wheels)"
# Resolve wheels on the HOST first (native speed), then install them inside the guest from a local
# directory: under emulation the metadata/resolution phase is the slow part. Byte-compilation is
# skipped in the guest and done on the host afterwards (bytecode is architecture independent for a
# given Python minor version), which is another large saving.
WHEELS="$WORK_DIR/wheels"
if [ -d "$WHEELS" ] && [ -n "$(ls -A "$WHEELS" 2>/dev/null)" ]; then
    echo "reusing the cached wheelhouse ($(ls -1 "$WHEELS" | wc -l) files)"
else
    mkdir -p "$WHEELS"
    if python3 -m pip download --dest "$WHEELS" --platform musllinux_1_2_aarch64 \
        --python-version 3.11 --only-binary=:all: hermes-agent >/dev/null 2>&1; then
        echo "wheelhouse ready ($(ls -1 "$WHEELS" | wc -l) wheels)"
    else
        echo "could not resolve wheels on the host; the guest will resolve them itself"
        rmdir "$WHEELS" 2>/dev/null || rm -rf "$WHEELS"
    fi
fi

if [ -d "$WHEELS" ]; then
    rm -rf "$ROOTFS/wheels"
    cp -a "$WHEELS" "$ROOTFS/wheels"
    guest_run /bin/sh -c "python3 -m pip install --break-system-packages --no-index --find-links=/wheels --no-compile hermes-agent"
    rm -rf "$ROOTFS/wheels"
else
    guest_run /bin/sh -c "python3 -m pip install --break-system-packages --no-cache-dir --no-compile hermes-agent"
fi

# Byte-compile on the host: same Python minor version, so the guest can use the result directly.
python3 -m compileall -q "$ROOTFS/usr/lib/python3.11/site-packages" >/dev/null 2>&1 || true

# pip puts the launcher in the distribution's bin directory; make it unambiguously resolvable.
guest_run /bin/sh -c 'H=$(command -v hermes); [ -n "$H" ] && ln -sf "$H" /usr/local/bin/hermes; command -v hermes; hermes --version'

say "6/9 Write guest services and configuration"
mkdir -p "$ROOTFS/bootstrap"
cp "$GUEST_DIR/api_server.py" "$GUEST_DIR/init_bootstrap.sh" "$GUEST_DIR/start_agent.sh" "$ROOTFS/bootstrap/"
chmod 755 "$ROOTFS/bootstrap/init_bootstrap.sh" "$ROOTFS/bootstrap/start_agent.sh"
chmod 644 "$ROOTFS/bootstrap/api_server.py"

cp "$GUEST_DIR/hermes-bootstrap.initd" "$ROOTFS/etc/init.d/hermes-bootstrap"
chmod 755 "$ROOTFS/etc/init.d/hermes-bootstrap"

# Host identity
echo "hermes-linux" > "$ROOTFS/etc/hostname"
cat > "$ROOTFS/etc/hosts" <<'HOSTS'
127.0.0.1 localhost hermes-linux
::1       localhost
HOSTS

# QEMU SLIRP gives the guest a static address; no DHCP client needed.
mkdir -p "$ROOTFS/etc/network"
cat > "$ROOTFS/etc/network/interfaces" <<'NET'
auto lo
iface lo inet loopback
auto eth0
iface eth0 inet static
  address 10.0.2.15
  netmask 255.255.255.0
  gateway 10.0.2.2
NET

# SLIRP's UDP DNS proxy is unreliable; force TCP and fall back to public DNS.
cat > "$ROOTFS/etc/resolv.conf" <<'DNS'
nameserver 10.0.2.3
nameserver 8.8.8.8
nameserver 8.8.4.4
options timeout:2 attempts:2 use-vc
DNS

cat > "$ROOTFS/etc/fstab" <<'FSTAB'
/dev/vda / ext4 rw,relatime 0 1
proc /proc proc defaults 0 0
sysfs /sys sysfs defaults 0 0
devtmpfs /dev devtmpfs defaults 0 0
devpts /dev/pts devpts gid=5,mode=620 0 0
shm /dev/shm tmpfs defaults 0 0
tmp /tmp tmpfs nosuid,nodev 0 0
FSTAB

# OpenRC runlevels
mkdir -p "$ROOTFS/etc/runlevels/sysinit" "$ROOTFS/etc/runlevels/boot" \
         "$ROOTFS/etc/runlevels/default" "$ROOTFS/etc/runlevels/shutdown"
for svc in devfs dmesg; do ln -sf "/etc/init.d/$svc" "$ROOTFS/etc/runlevels/sysinit/$svc"; done
for svc in modules sysctl hostname bootmisc syslog; do
    [ -e "$ROOTFS/etc/init.d/$svc" ] && ln -sf "/etc/init.d/$svc" "$ROOTFS/etc/runlevels/boot/$svc"
done
for svc in networking hermes-bootstrap; do ln -sf "/etc/init.d/$svc" "$ROOTFS/etc/runlevels/default/$svc"; done

say "7/9 Preconfigure the agent (API server on 0.0.0.0:8642, audio routes if bundled)"
mkdir -p "$ROOTFS/root/.hermes"
PLUGIN_SRC="$REPO_DIR/agent-side/hermes-audio-api"
if [ -f "$PLUGIN_SRC/plugin.yaml" ]; then
    mkdir -p "$ROOTFS/root/.hermes/plugins/hermes-audio-api"
    cp "$PLUGIN_SRC/__init__.py" "$PLUGIN_SRC/plugin.yaml" "$ROOTFS/root/.hermes/plugins/hermes-audio-api/"
    echo "bundled the audio-routes plugin: hermes-audio-api"
fi
guest_run /bin/sh -c '
    export HERMES_HOME=/root/.hermes
    hermes config set api_server.enabled true || true
    hermes config set api_server.host 0.0.0.0 || true
    hermes config set api_server.port 8642 || true
    if [ -d "$HERMES_HOME/plugins/hermes-audio-api" ]; then
        hermes plugins enable hermes-audio-api || true
    fi
    true
'

say "8/9 Clean the image (no host traces, no caches)"
rm -f "$ROOTFS/etc/resolv.conf.orig"
rm -rf "$ROOTFS/var/cache/apk" "$ROOTFS/root/.cache" "$ROOTFS/root/.ash_history" "$ROOTFS/tmp/"*
guest_run /bin/sh -c '
    python3 -m pip cache purge >/dev/null 2>&1 || true
    rm -rf /root/.cache
    true
'
find "$ROOTFS/root/.hermes" -name "__pycache__" -type d -prune -exec rm -rf {} + 2>/dev/null || true
cleanup

say "9/9 Pack the disk (ext4 -> qcow2 -> gzip)"
rm -f "$RAW" "$QCOW"
truncate -s "$DISK_SIZE" "$RAW"
mke2fs -F -q -t ext4 -L hermes-root -m 0 -d "$ROOTFS" "$RAW"
qemu-img convert -f raw -O qcow2 -c "$RAW" "$QCOW"
gzip -9 -c "$QCOW" > "$OUT"

printf '\nguest image: %s (%s)\n' "$OUT" "$(du -h "$OUT" | cut -f1)"
printf 'sha256: %s\n' "$(sha256sum "$OUT" | cut -d' ' -f1)"
qemu-img info "$QCOW"
