#!/bin/bash
# Build the OneZion Clip APK without Gradle (same approach as ../app/build.sh).
set -euo pipefail

BT=/opt/homebrew/share/android-commandlinetools
PLATFORM=$BT/platforms/android-36
BTools=$BT/build-tools/36.0.0
OUT=${1:-"$PWD/build/PhoneClip.apk"}

HERE="$(cd "$(dirname "$0")" && pwd)"
BUILD="$HERE/build"
rm -rf "$BUILD"
mkdir -p "$BUILD/compiled" "$BUILD/dex" "$BUILD/apk"

echo "==> compiling java"
find "$HERE/src" -name '*.java' > "$BUILD/sources.txt"
javac --release 17 -classpath "$PLATFORM/android.jar" \
  -d "$BUILD/compiled" @"$BUILD/sources.txt"

echo "==> packaging resources"
"$BTools/aapt2" compile --dir "$HERE/res" -o "$BUILD/apk/res.zip"

echo "==> linking resources"
"$BTools/aapt2" link \
  -o "$BUILD/apk/base.apk" \
  -I "$PLATFORM/android.jar" \
  --manifest "$HERE/AndroidManifest.xml" \
  -R "$BUILD/apk/res.zip" \
  --java "$BUILD/gen" \
  --min-sdk-version 29 \
  --target-sdk-version 36 \
  --auto-add-overlay

echo "==> dexing"
find "$BUILD/compiled" "$BUILD/gen" -name '*.class' > "$BUILD/classes.txt" 2>/dev/null || true
"$BTools/d8" @"$BUILD/classes.txt" --min-api 29 --output "$BUILD/dex"
( cd "$BUILD/dex" && zip -q "$BUILD/apk/base.apk" classes.dex )

echo "==> signing"
KS="$HOME/.android/debug.keystore"
if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -storepass android -keypass android \
    -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
fi
"$BTools/zipalign" -f 4 "$BUILD/apk/base.apk" "$BUILD/apk/aligned.apk"
mkdir -p "$(dirname "$OUT")"
"$BTools/apksigner" sign \
  --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --ks-key-alias androiddebugkey \
  --out "$OUT" "$BUILD/apk/aligned.apk"

echo "==> built $OUT"
ls -la "$OUT"
