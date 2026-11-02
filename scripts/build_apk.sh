#!/bin/sh
# Build the app APK. Override JAVA_HOME / ANDROID_HOME if your toolchain lives elsewhere.
#
#   ./scripts/build_apk.sh                 # assembleDebug
#   LOG=/root/cbx/logs/apk.log ./scripts/build_apk.sh
#                                          # also write the whole build to that file
#                                          # (its folder is created if missing)
#
# When no guest image is in the tree, the image of the current release is restored first
# (scripts/fetch_released_image.sh) and its extraction marker is passed to Gradle, so the APK boots
# the same guest and installed phones keep theirs.
set -eu

REPO_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)

if [ -n "${LOG:-}" ]; then
    mkdir -p "$(dirname -- "$LOG")"
    echo "build log: $LOG"
    # Re-run this script with everything going to the log and the screen.
    LOG= "$0" "$@" 2>&1 | tee "$LOG"
    exit "$(tail -1 "$LOG" | sed -n 's/^BUILD EXIT //p' | grep . || echo 1)"
fi

export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}"
export ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"
export PATH="$JAVA_HOME/bin:$PATH"

ASSETS="$REPO_DIR/app/src/main/assets/vm"
EXTRA=""
if [ -f "$ASSETS/base.qcow2.gz" ]; then
    echo "guest image: $ASSETS/base.qcow2.gz (built locally)"
else
    echo "guest image: none built locally - using the current release's"
    MARKER=$("$REPO_DIR/scripts/fetch_released_image.sh")
    EXTRA="-PguestImageSha256=$MARKER"
fi

[ -f "$REPO_DIR/local.properties" ] || echo "sdk.dir=$ANDROID_HOME" > "$REPO_DIR/local.properties"

cd "$REPO_DIR"
set +e
# shellcheck disable=SC2086
./gradlew "${@:-assembleDebug}" $EXTRA --no-daemon
STATUS=$?
set -e

APK="$REPO_DIR/app/build/outputs/apk/debug/app-debug.apk"
if [ "$STATUS" -eq 0 ] && [ -f "$APK" ]; then
    printf '\nAPK: %s (%s)\n' "$APK" "$(du -h "$APK" | cut -f1)"
fi
echo "BUILD EXIT $STATUS"
exit "$STATUS"
