#!/bin/sh
# Build the app APK. Override JAVA_HOME / ANDROID_HOME if your toolchain lives elsewhere.
set -eu

REPO_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)

export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}"
export ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"
export PATH="$JAVA_HOME/bin:$PATH"

IMAGE="$REPO_DIR/app/src/main/assets/vm/base.qcow2.gz"
if [ ! -f "$IMAGE" ]; then
    echo "warning: $IMAGE is missing."
    echo "         Build the guest image first: ./image/build_guest_image.sh"
    echo "         (without it the app installs but the VM has no disk to boot.)"
fi

[ -f "$REPO_DIR/local.properties" ] || echo "sdk.dir=$ANDROID_HOME" > "$REPO_DIR/local.properties"

cd "$REPO_DIR"
./gradlew "${@:-assembleDebug}" --no-daemon

APK="$REPO_DIR/app/build/outputs/apk/debug/app-debug.apk"
if [ -f "$APK" ]; then
    printf '\nAPK: %s (%s)\n' "$APK" "$(du -h "$APK" | cut -f1)"
fi
