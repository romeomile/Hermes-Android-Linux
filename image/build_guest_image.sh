#!/bin/sh
# Build the guest disk image (base.qcow2.gz) for Hermes Android Linux.
#
# The guest is a real Alpine Linux aarch64 root filesystem with Hermes Agent
# preinstalled, packed into a compressed qcow2 disk that the app extracts and
# QEMU boots.
#
# Hermes Agent does NOT come from a PyPI wheel: pip/PyPI is not a supported
# distribution path for it (setup.py refuses to build a wheel outside a Nix
# build and the wheel omits the bundled assets), so the image is built from the
# upstream Git tag the app targets, plus the dashboard SPA built from that same
# source on this host.
#
# Runs on any Linux host (x86_64 included) - no ARM hardware, no cross-Docker.
# Requirements:
#   * root (the rootfs is populated through a chroot)
#   * e2fsprogs  (mke2fs -d populates the filesystem from a directory, no loop mount)
#   * qemu-utils (qemu-img convert)
#   * node + npm (the dashboard SPA is built from source)
#   * qemu-user-static with binfmt_misc registered for qemu-aarch64, so the
#     guest's own apk/pip run during the build:
#       apt-get install -y qemu-user-static binfmt-support
#       update-binfmts --enable qemu-aarch64
set -eu

REPO_DIR="${REPO_DIR:-$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)}"
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
# py3-setuptools + py3-wheel are required because the Hermes source is installed
# with --no-build-isolation (its PEP 517 backend is setuptools).
PACKAGES="alpine-base openrc bash busybox-extras curl git ripgrep nano less procps \
coreutils findutils grep sed tar gzip xz bzip2 tzdata ca-certificates python3 py3-pip \
py3-setuptools py3-wheel"

# The upstream release the mobile shell targets (internal version 0.21.3).
HERMES_TAG="${HERMES_TAG:-v2026.9.14}"
HERMES_REPO="${HERMES_REPO:-https://github.com/NousResearch/hermes-agent.git}"
HERMES_SRC="$WORK_DIR/hermes-src"
WHEELS="$WORK_DIR/wheels"
SPA_OUT="$HERMES_SRC/hermes_cli/web_dist"

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
for required in api_server.py init_bootstrap.sh start_dashboard.sh relay.py hermes-bootstrap.initd; do
    [ -f "$GUEST_DIR/$required" ] || { echo "error: missing $GUEST_DIR/$required" >&2; exit 1; }
done

mkdir -p "$WORK_DIR" "$OUT_DIR"
ROOTFS="$WORK_DIR/rootfs"
RAW="$WORK_DIR/base.raw"
QCOW="$WORK_DIR/base.qcow2"
TARBALL="$WORK_DIR/alpine-minirootfs.tar.gz"

say "1/10 Fetch Alpine ${ALPINE_VERSION} aarch64 minirootfs"
if [ -f "$TARBALL" ] && echo "$MINIROOTFS_SHA256  $TARBALL" | sha256sum -c - >/dev/null 2>&1; then
    echo "cached and verified: $TARBALL"
else
    curl -fsSL -o "$TARBALL" "$MINIROOTFS_URL"
    echo "$MINIROOTFS_SHA256  $TARBALL" | sha256sum -c - || { echo "error: checksum mismatch" >&2; exit 1; }
fi

say "2/10 Unpack the root filesystem"
rm -rf "$ROOTFS"
mkdir -p "$ROOTFS"
tar xzf "$TARBALL" -C "$ROOTFS"
cp /etc/resolv.conf "$ROOTFS/etc/resolv.conf"
cat > "$ROOTFS/etc/apk/repositories" <<REPOS
https://dl-cdn.alpinelinux.org/alpine/${ALPINE_BRANCH}/main
https://dl-cdn.alpinelinux.org/alpine/${ALPINE_BRANCH}/community
REPOS

say "3/10 Enter the guest (bind mounts for the chroot)"
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

say "4/10 Install guest packages with the guest's own apk"
# shellcheck disable=SC2086
guest_run /sbin/apk add --no-cache $PACKAGES

say "5/10 Fetch Hermes Agent ${HERMES_TAG} and build the dashboard SPA on the host"
if [ -d "$HERMES_SRC/.git" ] && [ "$(git -C "$HERMES_SRC" describe --tags 2>/dev/null || true)" = "$HERMES_TAG" ]; then
    echo "reusing the cached checkout at $HERMES_SRC"
else
    rm -rf "$HERMES_SRC"
    git clone --depth 1 --branch "$HERMES_TAG" "$HERMES_REPO" "$HERMES_SRC"
fi
[ -f "$HERMES_SRC/pyproject.toml" ] || { echo "error: no pyproject.toml in the ${HERMES_TAG} checkout" >&2; exit 1; }
TAG_VERSION=$(sed -n 's/^version = "\(.*\)"/\1/p' "$HERMES_SRC/pyproject.toml" | head -1)
echo "source: $HERMES_SRC"
echo "source version: ${TAG_VERSION:-unknown} (tag $HERMES_TAG, $(git -C "$HERMES_SRC" rev-parse HEAD))"

# The SPA source lives in web/ and its build outputs to the Python package's web_dist
# (web/vite.config.ts: outDir "../hermes_cli/web_dist"). web/ carries no lockfile at this
# tag, so `npm ci` cannot be used; `npm install` + `npm run build` is the documented build.
SPA_SRC="$HERMES_SRC/web"
[ -f "$SPA_SRC/package.json" ] || {
    echo "error: no SPA source at $SPA_SRC in $HERMES_TAG - refusing to invent one" >&2; exit 1; }
if [ -f "$SPA_OUT/index.html" ]; then
    echo "reusing the built SPA ($(ls -1 "$SPA_OUT" | wc -l) entries, $(du -sh "$SPA_OUT" | cut -f1))"
else
    command -v npm >/dev/null || { echo "error: npm is required to build the dashboard SPA" >&2; exit 1; }
    ( cd "$SPA_SRC" && npm install --no-audit --no-fund && npm run build )
fi
[ -f "$SPA_OUT/index.html" ] || { echo "error: the SPA build produced no $SPA_OUT/index.html" >&2; exit 1; }
echo "SPA ready: $SPA_OUT ($(du -sh "$SPA_OUT" | cut -f1))"

say "6/10 Resolve the runtime wheels for musl/aarch64 on the host"
# Resolve on the HOST first (native speed), then install inside the guest from a local directory:
# under emulation the metadata/resolution phase is the slow part. The local source directory is
# passed to pip so the resolution is the one the tag itself declares (exact pins), not a guess.
if [ -f "$WHEELS/.complete" ] \
    && [ "$(cat "$WHEELS/.complete" 2>/dev/null || echo 0)" = "$(ls -1 "$WHEELS"/*.whl 2>/dev/null | wc -l)" ] \
    && [ "$(cat "$WHEELS/.complete" 2>/dev/null || echo 0)" -ge 40 ]; then
    echo "reusing the cached wheelhouse ($(ls -1 "$WHEELS"/*.whl | wc -l) wheels)"
else
    rm -rf "$WHEELS"
    mkdir -p "$WHEELS"
    # both musllinux tags: some pinned packages only ship musllinux_1_1
    python3 -m pip download --dest "$WHEELS" \
        --platform musllinux_1_1_aarch64 --platform musllinux_1_2_aarch64 \
        --python-version 3.11 --only-binary=:all: "$HERMES_SRC" \
        || { echo "error: could not resolve the runtime wheels on the host" >&2; exit 1; }
    WHEEL_COUNT=$(ls -1 "$WHEELS"/*.whl | wc -l)
    [ "$WHEEL_COUNT" -ge 40 ] || { echo "error: only $WHEEL_COUNT wheels resolved" >&2; exit 1; }
    printf '%s\n' "$WHEEL_COUNT" > "$WHEELS/.complete"
    echo "wheelhouse ready ($WHEEL_COUNT wheels, $(du -sh "$WHEELS" | cut -f1))"
fi

# The tag declares its own PEP 517 backend in pyproject's [build-system] (setuptools + wheel).
# --no-build-isolation means the GUEST's backend builds the source tree, so that exact backend
# has to be in the wheelhouse (the only package source with --no-index) and installed in the
# guest first: Alpine's python3-setuptools is far older than the pyproject metadata
# (PEP 639 license/license-files) needs and fails metadata generation with
# "project.license must be valid exactly by one definition".
BUILD_REQUIRES=$(python3 - "$HERMES_SRC/pyproject.toml" <<'PY'
import sys, tomllib

data = tomllib.load(open(sys.argv[1], "rb"))
print(" ".join(data.get("build-system", {}).get("requires", [])) or "setuptools wheel")
PY
)
echo "build backend required by the tag: $BUILD_REQUIRES"
# shellcheck disable=SC2086
python3 -m pip download --dest "$WHEELS" \
    --platform musllinux_1_1_aarch64 --platform musllinux_1_2_aarch64 \
    --python-version 3.11 --only-binary=:all: $BUILD_REQUIRES \
    || { echo "error: could not resolve the build backend wheels on the host" >&2; exit 1; }
# Keep the completeness marker honest: it is a count of what the directory holds.
ls -1 "$WHEELS"/*.whl | wc -l > "$WHEELS/.complete"
echo "wheelhouse: $(ls -1 "$WHEELS"/*.whl | wc -l) wheels ($(du -sh "$WHEELS" | cut -f1))"

say "7/10 Install Hermes Agent inside the guest (wheels + tag source)"
rm -rf "$ROOTFS/wheels" "$ROOTFS/hermes-src"
cp -a "$WHEELS" "$ROOTFS/wheels"
rm -f "$ROOTFS/wheels/.complete"
mkdir -p "$ROOTFS/hermes-src"
# Copy the source without what the install does not need and the image must not carry:
# git metadata, node_modules, and the test/eval/website trees.
tar -C "$HERMES_SRC" -cf - \
    --exclude=./.git \
    --exclude=./node_modules \
    --exclude=./web/node_modules \
    --exclude=./ui-tui \
    --exclude=./tests \
    --exclude=./tests-js \
    --exclude=./evals \
    --exclude=./website \
    --exclude=./apps \
    . | tar -C "$ROOTFS/hermes-src" -xf -

# HERMES_NIX_BUILD=1 is the packaging escape hatch setup.py accepts for a wheel build outside
# Nix; --no-build-isolation uses the guest's own setuptools/wheel.
# Upgrade that backend to the version the tag declares FIRST: py3-setuptools from Alpine (68.x)
# is older than this pyproject's metadata (PEP 639 license/license-files) needs, and with
# --no-index the wheelhouse is the only package source.
guest_run /bin/sh -c "python3 -m pip install --break-system-packages --no-index --find-links=/wheels --no-compile --upgrade $BUILD_REQUIRES"
guest_run /bin/sh -c 'python3 -m pip show setuptools wheel 2>/dev/null | grep -E "^(Name|Version):"'
guest_run /bin/sh -c 'HERMES_NIX_BUILD=1 python3 -m pip install \
    --break-system-packages --no-index --find-links=/wheels --no-build-isolation --no-compile \
    /hermes-src'

# The wheel deliberately carries no bundled assets (skills, optional-skills, optional-mcps,
# locales) and no web_dist - the packaging wrappers point env vars at them instead
# (nix/hermes-agent.nix). Resolve where the installed package really lives and put the assets
# next to it, which is where the runtime defaults look (hermes_constants._packaged_dir,
# web_server.WEB_DIST). Verified below, not assumed.
PKG_PARENT=$(guest_run /bin/sh -c 'python3 -c "import hermes_cli, os; print(os.path.dirname(os.path.dirname(hermes_cli.__file__)))"')
case "$PKG_PARENT" in
    /*) ;;
    *) echo "error: could not resolve the installed package directory in the guest" >&2; exit 1 ;;
esac
echo "installed package root: $PKG_PARENT"
for asset in skills optional-skills optional-mcps locales; do
    [ -d "$HERMES_SRC/$asset" ] || continue
    rm -rf "$ROOTFS$PKG_PARENT/$asset"
    cp -a "$HERMES_SRC/$asset" "$ROOTFS$PKG_PARENT/$asset"
    echo "baked $asset -> $PKG_PARENT/$asset ($(du -sh "$HERMES_SRC/$asset" | cut -f1))"
done
rm -rf "$ROOTFS$PKG_PARENT/hermes_cli/web_dist"
cp -a "$SPA_OUT" "$ROOTFS$PKG_PARENT/hermes_cli/web_dist"
[ -f "$ROOTFS$PKG_PARENT/hermes_cli/web_dist/index.html" ] || {
    echo "error: the SPA is not in the installed package ($PKG_PARENT/hermes_cli/web_dist)" >&2; exit 1; }
echo "baked the SPA -> $PKG_PARENT/hermes_cli/web_dist ($(du -sh "$ROOTFS$PKG_PARENT/hermes_cli/web_dist" | cut -f1))"

rm -rf "$ROOTFS/hermes-src" "$ROOTFS/wheels"

# Byte-compile on the host: same Python minor version, so the guest can use the result directly.
python3 -m compileall -q "$ROOTFS$PKG_PARENT" >/dev/null 2>&1 || true

# pip puts the launcher in the distribution's bin directory; make it unambiguously resolvable.
guest_run /bin/sh -c 'H=$(command -v hermes); [ -n "$H" ] && ln -sf "$H" /usr/local/bin/hermes; command -v hermes'
GUEST_VERSION=$(guest_run /bin/sh -c 'hermes --version 2>&1 | head -1' || true)
echo "guest hermes --version: ${GUEST_VERSION:-<no output>}"
case "$GUEST_VERSION" in
    *"${TAG_VERSION}"*) echo "version check OK ($TAG_VERSION)" ;;
    *) echo "error: guest hermes reports '${GUEST_VERSION:-<nothing>}' but $HERMES_TAG is $TAG_VERSION" >&2; exit 1 ;;
esac

say "8/10 Write guest services and configuration"
mkdir -p "$ROOTFS/bootstrap"
cp "$GUEST_DIR/api_server.py" "$GUEST_DIR/init_bootstrap.sh" \
   "$GUEST_DIR/start_dashboard.sh" "$GUEST_DIR/relay.py" "$ROOTFS/bootstrap/"
chmod 755 "$ROOTFS/bootstrap/init_bootstrap.sh" "$ROOTFS/bootstrap/start_dashboard.sh"
chmod 644 "$ROOTFS/bootstrap/api_server.py" "$ROOTFS/bootstrap/relay.py"

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

say "9/10 Preconfigure the agent and clean the image"
# The agent's config is deliberately minimal: no api_server block, no host values, no keys.
# The dashboard is the app's UI surface and it is started by the bootstrap, not configured here.
mkdir -p "$ROOTFS/root/.hermes"
PLUGIN_SRC="$REPO_DIR/agent-side/hermes-audio-api"
if [ -f "$PLUGIN_SRC/plugin.yaml" ]; then
    mkdir -p "$ROOTFS/root/.hermes/plugins/hermes-audio-api"
    cp "$PLUGIN_SRC/__init__.py" "$PLUGIN_SRC/plugin.yaml" "$ROOTFS/root/.hermes/plugins/hermes-audio-api/"
    echo "bundled the audio-routes plugin: hermes-audio-api"
fi
guest_run /bin/sh -c '
    export HERMES_HOME=/root/.hermes
    if [ -d "$HERMES_HOME/plugins/hermes-audio-api" ]; then
        hermes plugins enable hermes-audio-api || true
    fi
    true
'

rm -f "$ROOTFS/etc/resolv.conf.orig"
rm -rf "$ROOTFS/var/cache/apk" "$ROOTFS/root/.cache" "$ROOTFS/root/.ash_history" "$ROOTFS/tmp/"*
guest_run /bin/sh -c '
    python3 -m pip cache purge >/dev/null 2>&1 || true
    rm -rf /root/.cache
    true
'
find "$ROOTFS/root/.hermes" -name "__pycache__" -type d -prune -exec rm -rf {} + 2>/dev/null || true
cleanup

say "10/10 Pack the disk (ext4 -> qcow2 -> gzip)"
rm -f "$RAW" "$QCOW"
truncate -s "$DISK_SIZE" "$RAW"
mke2fs -F -q -t ext4 -L hermes-root -m 0 -d "$ROOTFS" "$RAW"
qemu-img convert -f raw -O qcow2 -c "$RAW" "$QCOW"
# Write through a temporary file: the output path is also read by other tooling (and by the
# boot test), so it must never be observable half-written.
gzip -9 -c "$QCOW" > "$OUT.tmp.$$"
mv "$OUT.tmp.$$" "$OUT"

printf '\nguest image: %s (%s)\n' "$OUT" "$(du -h "$OUT" | cut -f1)"
printf 'sha256: %s\n' "$(sha256sum "$OUT" | cut -d' ' -f1)"
qemu-img info "$QCOW"
