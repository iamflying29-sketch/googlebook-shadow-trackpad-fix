#!/bin/bash
# Builds the test tools from the CURRENT helper sources (never changes them): copies them, swaps
# the clock for a controllable one, adds a dry-run event validator, compiles to classes.dex.
#   build-fuzz.sh && adb push /tmp/shadowtrackpad-fuzz-build/classes.dex /data/local/tmp/fuzz.dex
#   adb shell CLASSPATH=/data/local/tmp/fuzz.dex app_process / local.shadowtrackpad.<Test> ...
#     Fuzz N SEED          random finger/click/timing sequences through Engine + Injector; checks
#                          every event and that nothing is ever left held (keys, buttons, drags)
#     Fuzz trace SEED      replay one sequence with a frame-by-frame log
#     ParserFuzz N SEED    kernel multitouch model -> MtParser; checks no finger is ever mixed up
#     ParserFuzz trace SEED
#     SecurityTest         attacks the RUNNING helper's control socket and status port
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
SRC=$HERE/../../helper/src/local/shadowtrackpad
B=/tmp/shadowtrackpad-fuzz-build
SDK=$HOME/tools/android-sdk
JAR=$SDK/platforms/android-35/android.jar
rm -rf "$B" && mkdir -p "$B/src/local/shadowtrackpad" "$B/cls"
cp "$SRC"/*.java "$HERE/Fuzz.java" "$HERE/ParserFuzz.java" "$HERE/SecurityTest.java" "$HERE/Clock.java" "$B/src/local/shadowtrackpad/"
cd "$B/src/local/shadowtrackpad"
sed -i 's/SystemClock\.uptimeMillis()/Clock.uptime()/g; s/SystemClock\.sleep(/Clock.sleep(/g' Engine.java Injector.java
python3 -I "$HERE/hooks.py" "$B/src/local/shadowtrackpad"
javac -Xlint:-options -source 11 -target 11 -cp "$JAR" -d "$B/cls" "$B"/src/local/shadowtrackpad/*.java
"$SDK/build-tools/35.0.0/d8" --min-api 33 --lib "$JAR" --output "$B" $(find "$B/cls" -name '*.class')
echo "$B/classes.dex"
