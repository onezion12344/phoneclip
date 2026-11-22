#!/bin/bash
# Install the native PhoneClip menu bar app + its login agent.
#   ./install-mac.sh            build and install
#   ./install-mac.sh uninstall  remove app + login agent (config kept)
#   ./install-mac.sh status     show agent state and config
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
APP=/Applications/PhoneClip.app
LABEL=ai.onezion.phoneclip.mac
PLIST="$HOME/Library/LaunchAgents/$LABEL.plist"
CONFIG="$HOME/Library/Application Support/PhoneClip/config.json"

case "${1:-install}" in
  uninstall)
    launchctl bootout "gui/$(id -u)/$LABEL" 2>/dev/null || true
    rm -f "$PLIST"
    pkill -f "PhoneClip.app/Contents/MacOS/PhoneClip" 2>/dev/null || true
    rm -rf "$APP"
    echo "removed $APP (config kept in ~/Library/Application Support/PhoneClip)"
    exit 0
    ;;
  status)
    launchctl print "gui/$(id -u)/$LABEL" 2>/dev/null | head -10 || echo "(agent not loaded)"
    [ -f "$CONFIG" ] && cat "$CONFIG"
    exit 0
    ;;
esac

# migrate away from the old Go daemon if it is still around
launchctl bootout "gui/$(id -u)/com.onezion.phoneclipd" 2>/dev/null || true
rm -f "$HOME/Library/LaunchAgents/com.onezion.phoneclipd.plist"
pkill -f "Application Support/PhoneClip/bin/phoneclipd" 2>/dev/null || true

"$HERE/mac-app/build-app.sh"

echo "==> installing to $APP"
pkill -f "PhoneClip.app/Contents/MacOS/PhoneClip" 2>/dev/null || true
sleep 1
rm -rf "$APP"
cp -R "$HERE/mac-app/build/PhoneClip.app" "$APP"
xattr -cr "$APP"
codesign --force --deep -s - "$APP" >/dev/null 2>&1 || true

cat > "$PLIST" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>$LABEL</string>
  <key>ProgramArguments</key>
  <array><string>$APP/Contents/MacOS/PhoneClip</string></array>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
  <key>LimitLoadToSessionType</key><string>Aqua</string>
  <key>StandardOutPath</key><string>/tmp/phoneclip-app.log</string>
  <key>StandardErrorPath</key><string>/tmp/phoneclip-app.log</string>
</dict>
</plist>
PLIST

launchctl bootout "gui/$(id -u)/$LABEL" 2>/dev/null || true
launchctl bootstrap "gui/$(id -u)" "$PLIST"
launchctl kickstart "gui/$(id -u)/$LABEL" 2>/dev/null || true

sleep 1
TOKEN=$(/usr/bin/python3 -c '
import json, os
print(json.load(open(os.path.expanduser("~/Library/Application Support/PhoneClip/config.json")))["token"])
')
PORT=$(/usr/bin/python3 -c '
import json, os
print(json.load(open(os.path.expanduser("~/Library/Application Support/PhoneClip/config.json")))["port"])
')
LAN=$(ipconfig getifaddr en0 2>/dev/null || true)
TS=$(/usr/local/bin/tailscale ip -4 2>/dev/null | head -1 || true)

echo
echo "PhoneClip.app is running in the menu bar."
echo "  Tailscale  http://${TS:-<none>}:$PORT"
echo "  LAN        http://${LAN:-<none>}:$PORT"
echo "  token      $TOKEN"
echo
echo "The same URL + token go into the Android app."
