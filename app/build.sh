#!/bin/bash
# Builds ShadowTrackpadFix.apk: the app plus the trackpad helper (local.shadowtrackpad.*), which the
# app starts on the device with "app_process" straight from this APK.
set -euo pipefail
cd "$(dirname "$0")"
SDK=$HOME/tools/android-sdk
BT=$SDK/build-tools/35.0.0
JAR=$SDK/platforms/android-35/android.jar
HELPER=../helper
KS=keystore.jks
# Password of your own signing keystore (created on the first build). Set KS_PASS to your own value.
KS_PASS="${KS_PASS:-change-me}"
VERSION_CODE=${VERSION_CODE:-$(( $(date +%s) / 60 ))}   # always increasing (no downgrade rejections)
VERSION_NAME=${VERSION_NAME:-1.$VERSION_CODE}

# A fresh build folder each time (nothing is ever deleted).
B=build/$(date +%Y%m%d-%H%M%S)
mkdir -p "$B/classes" "$B/gen" assets
# assets/start.sh is generated: the supervisor script from the helper is bundled into the APK.
cp "$HELPER/device/start.sh" assets/start.sh
"$BT/aapt2" compile --dir res -o $B/res.zip
"$BT/aapt2" link -o $B/unsigned.apk -I "$JAR" --manifest AndroidManifest.xml -A assets \
    --min-sdk-version 34 --target-sdk-version 35 --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
    --java $B/gen $B/res.zip
javac -Xlint:-options -source 11 -target 11 -cp "$JAR" -d $B/classes \
    $B/gen/local/shadowtrackpadfix/R.java src/local/shadowtrackpadfix/*.java "$HELPER"/src/local/shadowtrackpad/*.java
"$BT/d8" --min-api 34 --lib "$JAR" --output "$B" $(find $B/classes -name '*.class')
python3 -I -c 'import sys, zipfile; zipfile.ZipFile(sys.argv[1], "a").write(sys.argv[2], "classes.dex")' \
    $B/unsigned.apk $B/classes.dex
"$BT/zipalign" -p -f 4 $B/unsigned.apk $B/aligned.apk
if [ ! -f "$KS" ]; then
    keytool -genkeypair -keystore "$KS" -storepass "$KS_PASS" -keypass "$KS_PASS" -alias shadowtrackpadfix \
        -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Shadow Trackpad Fix (local build)" >/dev/null 2>&1
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass "pass:$KS_PASS" --out ShadowTrackpadFix.apk $B/aligned.apk
"$BT/apksigner" verify ShadowTrackpadFix.apk
echo "Built $(pwd)/ShadowTrackpadFix.apk"
