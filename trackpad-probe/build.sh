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

rm -rf build && mkdir -p build/classes build/gen

"$BT/aapt2" compile --dir res -o build/res.zip
"$BT/aapt2" link -o build/unsigned.apk -I "$JAR" --manifest AndroidManifest.xml \
    --min-sdk-version 33 --target-sdk-version 35 --version-code 6 --version-name 1.5 \
    --java build/gen build/res.zip

javac -Xlint:-options -source 11 -target 11 -cp "$JAR" -d build/classes \
    build/gen/local/trackpadprobe/R.java src/local/trackpadprobe/*.java
"$BT/d8" --min-api 33 --lib "$JAR" --output build $(find build/classes -name '*.class')

python3 -I -c 'import sys, zipfile; zipfile.ZipFile(sys.argv[1], "a").write(sys.argv[2], "classes.dex")' \
    build/unsigned.apk build/classes.dex
"$BT/zipalign" -p -f 4 build/unsigned.apk build/aligned.apk

if [ ! -f "$KS" ]; then
    keytool -genkeypair -keystore "$KS" -storepass "$KS_PASS" -keypass "$KS_PASS" -alias trackpadprobe \
        -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Trackpad Probe (local build)"
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass "pass:$KS_PASS" --out TrackpadProbe.apk build/aligned.apk
"$BT/apksigner" verify TrackpadProbe.apk
echo "Built $(pwd)/TrackpadProbe.apk"
