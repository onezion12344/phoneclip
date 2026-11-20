#!/bin/bash
# Build + install the PhoneClip APK on the connected phone and wire it to the Mac daemon.
# Usage: ./deploy-phone.sh [serial]
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
APP="$HOME/Library/Application Support/PhoneClip"
PKG=ai.onezion.phoneclip

TOKEN=$(python3 -c 'import json;print(json.load(open("'"$APP"'/config.json"))["token"])')
TSIP=$(python3 -c 'import json;print(json.load(open("'"$APP"'/config.json"))["listen_ip"])')
PORT=$(python3 -c 'import json;print(json.load(open("'"$APP"'/config.json"))["port"])')
LANIP=$(ipconfig getifaddr en0 2>/dev/null || true)

SERIAL=${1:-}
if [ -z "$SERIAL" ]; then
  SERIAL=$(adb devices | awk 'NR>1 && $2=="device" {print $1; exit}')
fi
if [ -z "$SERIAL" ]; then
  echo "No device. Phone is offline — bring up remote adb first:"
  echo "  ~/.claude/skills/oneplus-open-onezion/scripts/op-adb"
  echo "(if v2rayNG owns the VPN slot, Tailscale must be started on the phone instead)"
  exit 1
fi
ADB=(adb -s "$SERIAL")

echo "==> device $SERIAL"
"${ADB[@]}" shell getprop ro.product.model

reachable() {
  "${ADB[@]}" shell "toybox nc -z -w 2 $1 $2 >/dev/null 2>&1; echo \$?" 2>/dev/null | tr -d '\r'
}
URL=""
if [ "$(reachable "$TSIP" "$PORT")" = "0" ]; then
  URL="http://$TSIP:$PORT"
elif [ -n "$LANIP" ] && [ "$(reachable "$LANIP" "$PORT")" = "0" ]; then
  URL="http://$LANIP:$PORT"
else
  URL="http://$TSIP:$PORT"
  echo "!! phone cannot reach the daemon yet (neither $TSIP nor ${LANIP:-no-lan});"
  echo "   check same Wi-Fi / Tailscale, then re-run."
fi
echo "==> server $URL"

echo "==> build"
"$HERE/build.sh" "$HERE/build/PhoneClip.apk" >/dev/null

echo "==> install"
"${ADB[@]}" install -r "$HERE/build/PhoneClip.apk"

echo "==> permissions"
"${ADB[@]}" shell pm grant $PKG android.permission.READ_MEDIA_IMAGES 2>/dev/null || true
"${ADB[@]}" shell pm grant $PKG android.permission.POST_NOTIFICATIONS 2>/dev/null || true
"${ADB[@]}" shell appops set $PKG READ_MEDIA_IMAGES allow 2>/dev/null || true
"${ADB[@]}" shell appops set $PKG POST_NOTIFICATION allow 2>/dev/null || true
"${ADB[@]}" shell appops set $PKG SYSTEM_ALERT_WINDOW allow 2>/dev/null || true
"${ADB[@]}" shell dumpsys deviceidle whitelist +$PKG >/dev/null 2>&1 || true
if ! "${ADB[@]}" shell dumpsys package $PKG 2>/dev/null | grep -q 'READ_MEDIA_IMAGES: granted=true'; then
  echo "!! 相册权限没能自动授予（非 root 的 ColorOS 限制）"
  echo "   请在手机上打开 OneZion Clip，点 Start 并允许「照片和视频」权限（只需一次）"
fi

echo "==> configure + start"
"${ADB[@]}" shell am start -n $PKG/.MainActivity \
  --es server "$URL" --es token "$TOKEN" --ez start true

sleep 2
echo "==> trigger test image"
"${ADB[@]}" shell am start -n $PKG/.MainActivity --ez test true
sleep 3

echo "==> daemon log"
tail -4 /tmp/phoneclipd.log

echo
echo "Reminder: 常驻自启需要在手机上手动打开一次（ColorOS 不提供 adb 开关）："
echo "  设置 → 应用 → 自启动 → OneZion Clip → 允许"
