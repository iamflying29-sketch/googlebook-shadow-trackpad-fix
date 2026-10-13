#!/bin/bash
# Builds shadowtrackpad.dex (run on the Googlebook via app_process as the adb shell user).
set -euo pipefail
cd "$(dirname "$0")"
SDK=$HOME/tools/android-sdk
JAR=$SDK/platforms/android-35/android.jar
# A fresh build folder each time (nothing is ever deleted).
B=build/$(date +%Y%m%d-%H%M%S)
mkdir -p "$B/classes"
javac -Xlint:-options -source 11 -target 11 -cp "$JAR" -d $B/classes src/local/shadowtrackpad/*.java
"$SDK/build-tools/35.0.0/d8" --min-api 33 --lib "$JAR" --output "$B" $(find $B/classes -name '*.class')
mv $B/classes.dex shadowtrackpad.dex
echo "Built $(pwd)/shadowtrackpad.dex"
