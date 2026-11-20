#!/bin/bash
# Install / uninstall the Mac side of OneZion Clip.
#   ./install.sh            install + start the daemon
#   ./install.sh uninstall  stop + remove the LaunchAgent (keeps config)
#   ./install.sh status     show config and recent log
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
APP="$HOME/Library/Application Support/PhoneClip"
PLIST="$HOME/Library/LaunchAgents/com.onezion.phoneclipd.plist"
LABEL="com.onezion.phoneclipd"
PORT=47411

cmd="${1:-install}"

uninstall() {
  launchctl bootout "gui/$(id -u)/$LABEL" 2>/dev/null || true
  rm -f "$PLIST"
  echo "stopped and removed $LABEL (config kept in $APP)"
}

status() {
  echo "config: $APP/config.json"
  cat "$APP/config.json" 2>/dev/null || echo "(missing)"
  echo "--- launchd"
  launchctl print "gui/$(id -u)/$LABEL" 2>/dev/null | head -12 || echo "(not loaded)"
  echo "--- log"
  tail -20 /tmp/phoneclipd.log 2>/dev/null || true
}

case "$cmd" in
  uninstall) uninstall; exit 0 ;;
  status) status; exit 0 ;;
esac

mkdir -p "$APP/bin" "$APP/cache" "$HOME/Pictures/PhoneClip"

echo "==> building daemon (go)"
( cd "$HERE/mac" && go build -trimpath -ldflags='-s -w' -o "$APP/bin/phoneclipd" main.go )

echo "==> building clipboard helper (swift)"
swiftc -O "$HERE/mac/clip.swift" -o "$APP/bin/phoneclip-clip"
swiftc -O "$HERE/mac/toast.swift" -o "$APP/bin/phoneclip-toast"

TOKEN=$(/usr/bin/python3 -c '
import json,sys,os
p=os.path.expanduser("~/Library/Application Support/PhoneClip/config.json")
try: print(json.load(open(p))["token"])
except Exception: pass
')
if [ -z "${TOKEN:-}" ]; then
  TOKEN=$(openssl rand -hex 16)
fi

TSIP=$(/usr/local/bin/tailscale ip -4 2>/dev/null | head -1 || true)
if [ -z "$TSIP" ]; then
  TSIP=$(/Applications/Tailscale.app/Contents/MacOS/Tailscale ip -4 2>/dev/null | head -1 || true)
fi
[ -z "$TSIP" ] && TSIP="127.0.0.1"

/usr/bin/python3 - "$APP/config.json" "$TOKEN" "$PORT" "$TSIP" <<'PY'
import json, sys
path, token, port, ip = sys.argv[1:5]
try:
    cfg = json.load(open(path))
except Exception:
    cfg = {}
cfg.update({"token": token, "port": int(port), "listen_ip": ip})
cfg.setdefault("notify", True)
cfg.setdefault("sound", False)
json.dump(cfg, open(path, "w"), indent=2)
PY

cat > "$PLIST" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>$LABEL</string>
  <key>ProgramArguments</key>
  <array><string>$APP/bin/phoneclipd</string></array>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
  <key>ThrottleInterval</key><integer>5</integer>
  <key>ProcessType</key><string>Background</string>
  <key>StandardOutPath</key><string>/tmp/phoneclipd.log</string>
  <key>StandardErrorPath</key><string>/tmp/phoneclipd.log</string>
</dict>
</plist>
PLIST

launchctl bootout "gui/$(id -u)/$LABEL" 2>/dev/null || true
launchctl bootstrap "gui/$(id -u)" "$PLIST"
launchctl kickstart -k "gui/$(id -u)/$LABEL"

sleep 1
LANIP=$(ipconfig getifaddr en0 2>/dev/null || true)
echo
echo "Mac side is up:"
echo "  Tailscale  http://$TSIP:$PORT"
echo "  LAN        http://${LANIP:-<no-en0>}:$PORT   (for the Ace, which has no Tailscale)"
echo "  token      $TOKEN"
echo
echo "Configure the phone app in one shot (once the APK is installed):"
echo "  adb shell am start -n ai.onezion.phoneclip/.MainActivity \\"
echo "    --es server http://$TSIP:$PORT --es token $TOKEN --ez start true"
