#!/bin/sh
# Builds the Android APK inside Docker (no local JDK/SDK needed).
set -e
cd "$(dirname "$0")"
docker build -t cpz-android docker
mkdir -p .build-cache/home .build-cache/gradle .build-cache/android
docker run --rm \
    -u "$(id -u):$(id -g)" \
    -e HOME=/cache/home -e GRADLE_USER_HOME=/cache/gradle -e ANDROID_USER_HOME=/cache/android \
    -v "$PWD/android:/src" -v "$PWD/.build-cache:/cache" -w /src \
    cpz-android gradle --no-daemon assembleDebug
APK=android/app/build/outputs/apk/debug/app-debug.apk
echo
echo "Built $APK"
echo "Install on your phone (USB debugging on):  adb install -r $APK"
