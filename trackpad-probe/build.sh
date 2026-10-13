#!/bin/bash
# Builds and signs TrackpadProbe.apk with the Android SDK in ~/tools/android-sdk.
set -euo pipefail

cd "$(dirname "$0")"
SDK=$HOME/tools/android-sdk
BT=$SDK/build-tools/35.0.0
JAR=$SDK/platforms/android-35/android.jar
KS=keystore.jks
# Password of your own signing keystore (created on the first build). Set KS_PASS to your own value.
KS_PASS="${KS_PASS:-change-me}"

# A fresh build folder each time (nothing is ever deleted).
B=build/$(date +%Y%m%d-%H%M%S)
mkdir -p "$B/classes" "$B/gen"

"$BT/aapt2" compile --dir res -o $B/res.zip
"$BT/aapt2" link -o $B/unsigned.apk -I "$JAR" --manifest AndroidManifest.xml \
    --min-sdk-version 33 --target-sdk-version 35 --version-code 6 --version-name 1.5 \
    --java $B/gen $B/res.zip

javac -Xlint:-options -source 11 -target 11 -cp "$JAR" -d $B/classes \
    $B/gen/local/trackpadprobe/R.java src/local/trackpadprobe/*.java
"$BT/d8" --min-api 33 --lib "$JAR" --output "$B" $(find $B/classes -name '*.class')

python3 -I -c 'import sys, zipfile; zipfile.ZipFile(sys.argv[1], "a").write(sys.argv[2], "classes.dex")' \
    $B/unsigned.apk $B/classes.dex
"$BT/zipalign" -p -f 4 $B/unsigned.apk $B/aligned.apk

if [ ! -f "$KS" ]; then
    keytool -genkeypair -keystore "$KS" -storepass "$KS_PASS" -keypass "$KS_PASS" -alias trackpadprobe \
        -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Trackpad Probe (local build)"
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass "pass:$KS_PASS" --out TrackpadProbe.apk $B/aligned.apk
"$BT/apksigner" verify TrackpadProbe.apk
echo "Built $(pwd)/TrackpadProbe.apk"
