#!/bin/sh
# Restore the guest disk a published release ships, instead of rebuilding it.
#
# Building the image needs a Linux host with binfmt/qemu-user and takes a long time; an app-only change
# does not need a new image at all. This unpacks assets/vm/base.qcow2 from the APK of the release named
# in image/RELEASED_IMAGE, checks its hash, and prints the extraction marker that release was built
# with. Pass that marker to Gradle so installs of the release keep their guest:
#
#   MARKER=$(scripts/fetch_released_image.sh)
#   ./gradlew assembleDebug -PguestImageSha256="$MARKER"
#
# Needs: gh (authenticated) or curl, unzip, sha256sum.
set -eu

REPO_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
SPEC="$REPO_DIR/image/RELEASED_IMAGE"
REPO="${GITHUB_REPOSITORY:-romeomile/Hermes-Android-Linux}"
DEST="$REPO_DIR/app/src/main/assets/vm/base.qcow2"

value() { sed -n "s/^$1=//p" "$SPEC" | head -1; }
TAG=$(value tag)
MARKER=$(value marker)
WANT=$(value qcow2)
[ -n "$TAG" ] && [ -n "$MARKER" ] && [ -n "$WANT" ] || { echo "error: $SPEC is incomplete" >&2; exit 1; }

if [ -f "$DEST" ] && [ "$(sha256sum "$DEST" | cut -d' ' -f1)" = "$WANT" ]; then
    echo "guest image already in place ($DEST)" >&2
    echo "$MARKER"
    exit 0
fi

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT INT TERM

echo "downloading the $TAG APK from $REPO" >&2
# The repository is public, so a plain download works anywhere; gh is the fallback for a private fork.
NAME="HermesLinux-${TAG#v}-debug.apk"
if ! curl -fsSL --retry 3 -o "$TMP/$NAME" "https://github.com/$REPO/releases/download/$TAG/$NAME"; then
    rm -f "$TMP/$NAME"
    command -v gh >/dev/null 2>&1 || { echo "error: could not download $NAME" >&2; exit 1; }
    gh release download "$TAG" -R "$REPO" -p '*-debug.apk' -D "$TMP" >&2
fi
APK=$(ls "$TMP"/*.apk | head -1)

unzip -q -o "$APK" assets/vm/base.qcow2 -d "$TMP/x"
GOT=$(sha256sum "$TMP/x/assets/vm/base.qcow2" | cut -d' ' -f1)
if [ "$GOT" != "$WANT" ]; then
    echo "error: base.qcow2 from $TAG hashes $GOT, expected $WANT" >&2
    exit 1
fi
mkdir -p "$(dirname "$DEST")"
mv "$TMP/x/assets/vm/base.qcow2" "$DEST"
echo "guest image restored from $TAG ($DEST)" >&2
echo "$MARKER"
