#!/bin/sh
# Builds the Android APK inside Docker (no local JDK/SDK needed).
#   ./build-apk.sh           debug build
#   ./build-apk.sh release   signed release build (key from ./make-signing-key.sh)
set -e
cd "$(dirname "$0")"
MODE=${1:-debug}
KEYS_DIR=${CPZ_KEYS_DIR:-$HOME/.android-signing/cardputerzero-remote}
EXTRA=""
case "$MODE" in
    debug) TASK=assembleDebug ;;
    release)
        TASK=assembleRelease
        if [ ! -f "$KEYS_DIR/keystore.properties" ]; then
            echo "No signing key in $KEYS_DIR. Run ./make-signing-key.sh first." >&2
            exit 1
        fi
        EXTRA="-v $KEYS_DIR:/keys:ro -e CPZ_SIGNING_PROPERTIES=/keys/keystore.properties"
        ;;
    *) echo "usage: $0 [debug|release]" >&2; exit 2 ;;
esac

docker build -t cpz-android docker
mkdir -p .build-cache/home .build-cache/gradle .build-cache/android
# shellcheck disable=SC2086
docker run --rm \
    -u "$(id -u):$(id -g)" \
    -e HOME=/cache/home -e GRADLE_USER_HOME=/cache/gradle -e ANDROID_USER_HOME=/cache/android \
    -v "$PWD/android:/src" -v "$PWD/.build-cache:/cache" $EXTRA -w /src \
    cpz-android gradle --no-daemon "$TASK"

APK=android/app/build/outputs/apk/$MODE/app-$MODE.apk
if [ "$MODE" = release ]; then
    docker run --rm -v "$PWD/android:/src:ro" cpz-android \
        sh -c "\$ANDROID_HOME/build-tools/34.0.0/apksigner verify --print-certs /src/app/build/outputs/apk/release/app-release.apk" \
        | grep -E 'Signer #1 certificate (DN|SHA-256)'
fi
echo
echo "Built $APK"
echo "Install on your phone (USB debugging on):  adb install -r $APK"
