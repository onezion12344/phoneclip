#!/bin/bash
# Build PhoneClip.app (native menu bar app, no dependencies beyond the SDK).
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
APP="$HERE/build/PhoneClip.app"

rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"

echo "==> compiling swift"
swiftc -O "$HERE/Sources/"*.swift \
  -o "$APP/Contents/MacOS/PhoneClip" \
  -framework AppKit -framework Network

cp "$HERE/Info.plist" "$APP/Contents/Info.plist"
V=$(cat "$HERE/VERSION" 2>/dev/null || echo 0.0.0)
plutil -replace CFBundleShortVersionString -string "$V" "$APP/Contents/Info.plist"
CODE=$(/usr/bin/python3 -c 'import sys;v=(sys.argv[1].split(".")+["0","0"])[:3];print(int(v[0])*10000+int(v[1])*100+int(v[2]))' "$V")
plutil -replace CFBundleVersion -string "$CODE" "$APP/Contents/Info.plist"

ICON="$HERE/../docs/icon.png"
if [ -f "$ICON" ]; then
  TMP="$(mktemp -d)"
  ICONSET="$TMP/AppIcon.iconset"
  mkdir -p "$ICONSET"
  for s in 16 32 128 256 512; do
    sips -z "$s" "$s" "$ICON" --out "$ICONSET/icon_${s}x${s}.png" >/dev/null
    d=$((s * 2))
    sips -z "$d" "$d" "$ICON" --out "$ICONSET/icon_${s}x${s}@2x.png" >/dev/null
  done
  iconutil -c icns "$ICONSET" -o "$APP/Contents/Resources/AppIcon.icns"
  rm -rf "$TMP"
fi

codesign --force --sign - "$APP" >/dev/null 2>&1 || true

echo "==> built $APP"
du -sh "$APP"
