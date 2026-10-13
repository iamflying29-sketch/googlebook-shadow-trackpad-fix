#!/bin/bash
# Builds shadowtrackpad.dex (run on the Googlebook via app_process as the adb shell user).
set -euo pipefail
cd "$(dirname "$0")"
SDK=$HOME/tools/android-sdk
JAR=$SDK/platforms/android-35/android.jar
rm -rf build && mkdir -p build/classes
javac -Xlint:-options -source 11 -target 11 -cp "$JAR" -d build/classes src/local/shadowtrackpad/*.java
"$SDK/build-tools/35.0.0/d8" --min-api 33 --lib "$JAR" --output build $(find build/classes -name '*.class')
mv build/classes.dex shadowtrackpad.dex
echo "Built $(pwd)/shadowtrackpad.dex"
