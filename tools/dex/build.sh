#!/bin/bash
# Builds keytest.dex from KeyTest.java. The doctor pushes it to the Googlebook and runs it with
# app_process to check that Android still lets the gesture shortcuts (Ctrl+Alt+Tab, Alt+Left and
# Alt+Right) reach apps.
set -euo pipefail
cd "$(dirname "$0")"
SDK=$HOME/tools/android-sdk
JAR=$SDK/platforms/android-35/android.jar
mkdir -p build/classes
javac -Xlint:-options -source 11 -target 11 -cp "$JAR" -d build/classes KeyTest.java
"$SDK/build-tools/35.0.0/d8" --min-api 33 --lib "$JAR" --output build $(find build/classes -name '*.class')
mv build/classes.dex keytest.dex
echo "Built $(pwd)/keytest.dex"
