#!/usr/bin/env bash
# Builds the debug APK from the command line — no Android Studio needed.
# Pins JDK 17 for this build only (AGP 8.x needs 17; the system `java` is left untouched).
set -euo pipefail
cd "$(dirname "$0")"

export JAVA_HOME="${JAVA_HOME_17:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
export ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"

if [ ! -f local.properties ]; then
  echo "sdk.dir=$ANDROID_HOME" > local.properties
fi

./gradlew assembleDebug "$@"

echo
echo "APK: $(pwd)/app/build/outputs/apk/debug/app-debug.apk"
