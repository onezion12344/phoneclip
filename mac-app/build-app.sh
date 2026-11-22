#!/bin/bash
# Build PhoneClip.app (native menu bar app, no dependencies beyond the SDK).
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
APP="$HERE/build/PhoneClip.app"

rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"

echo "==> compiling swift"
swiftc -O "$HERE/Sources/Clipboard.swift" "$HERE/Sources/Server.swift" "$HERE/Sources/main.swift" \
  -o "$APP/Contents/MacOS/PhoneClip" \
  -framework AppKit -framework Network -framework ServiceManagement

cp "$HERE/Info.plist" "$APP/Contents/Info.plist"

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
