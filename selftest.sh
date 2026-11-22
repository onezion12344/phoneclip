#!/bin/bash
# One-command device self-test for the phone features added in 1.4:
#   sidecar list/get, phone transcription (mock endpoint), 720p compression.
# Usage: ./selftest.sh [adb-serial]
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
APP="$HOME/Library/Application Support/PhoneClip"
PKG=ai.onezion.phoneclip
PORT=47600

command -v ffmpeg >/dev/null || { echo "ffmpeg missing"; exit 1; }
command -v ffprobe >/dev/null || { echo "ffprobe missing"; exit 1; }
if /usr/libexec/ApplicationFirewall/socketfilterfw --getglobalstate 2>/dev/null | grep -q enabled; then
  echo "note: macOS firewall is ON; if the transcription test times out, allow python3"
  echo "      (System Settings → Network → Firewall → Options) or use Tailscale serve."
fi

SERIAL=${1:-$(adb devices | awk 'NR>1 && $2=="device"{print $1; exit}')}
if [ -z "$SERIAL" ]; then
  echo "no device. enable wireless debugging (or plug USB), then re-run."
  exit 1
fi
ADB=(adb -s "$SERIAL")
TOKEN=$(python3 -c 'import json;print(json.load(open("'"$APP"'/config.json"))["token"])')
TSIP=$(python3 -c 'import json;print(json.load(open("'"$APP"'/config.json"))["listen_ip"])')
TDIR=$(python3 -c 'import json,os;c=json.load(open("'"$APP"'/config.json"));print(c.get("transcript_dir") or os.path.expanduser("~/Documents/PhoneClip/Transcripts"))')
LANIP=$(ipconfig getifaddr en0 2>/dev/null || true)
BASE="http://127.0.0.1:47411"

pass=0; fail=0
ok() { echo "PASS  $*"; pass=$((pass+1)); }
no() { echo "FAIL  $*"; fail=$((fail+1)); }

reachable() { "${ADB[@]}" shell "toybox nc -z -w 2 $1 47411 >/dev/null 2>&1; echo \$?" | tr -d '\r'; }
if [ -n "${SERVER_URL:-}" ]; then
  URL=$SERVER_URL
  PHONE_IP=$(printf '%s' "$URL" | sed -E 's#^https?://([^:/]+).*#\1#')
else
  PHONE_IP=""
  if [ "$(reachable "$TSIP")" = "0" ]; then PHONE_IP=$TSIP
  elif [ -n "$LANIP" ] && [ "$(reachable "$LANIP")" = "0" ]; then PHONE_IP=$LANIP
  else echo "phone cannot reach the Mac (Tailscale/LAN); fix network first"; exit 1; fi
  URL="http://$PHONE_IP:47411"
fi
echo "device $SERIAL  url $URL"

"$HERE/build.sh" "$HERE/build/PhoneClip.apk" >/dev/null
"${ADB[@]}" install -r "$HERE/build/PhoneClip.apk" >/dev/null
"${ADB[@]}" shell pm grant $PKG android.permission.READ_MEDIA_IMAGES 2>/dev/null || true
"${ADB[@]}" shell pm grant $PKG android.permission.POST_NOTIFICATIONS 2>/dev/null || true
"${ADB[@]}" shell appops set $PKG MANAGE_EXTERNAL_STORAGE allow 2>/dev/null || true
"${ADB[@]}" shell dumpsys deviceidle whitelist +$PKG >/dev/null 2>&1 || true
if ! "${ADB[@]}" shell appops get $PKG MANAGE_EXTERNAL_STORAGE 2>/dev/null | grep -q allow; then
  echo "!! 所有文件访问未开启，Sidecar/压缩测试无法通过。"
  echo "   正在打开设置页，请手动允许 OneZion Clip："
  "${ADB[@]}" shell am start -a android.settings.MANAGE_APP_ALL_FILES_ACCESS_PERMISSION \
    -d package:$PKG >/dev/null 2>&1 || true
  exit 1
fi
"${ADB[@]}" shell am start -n $PKG/.MainActivity \
  --es server "$URL" --es token "$TOKEN" --ez start true >/dev/null

# test media: 1080p source (so compression has something to shrink) + audio only
ffmpeg -y -loglevel error -f lavfi -i testsrc=size=1920x1080:rate=30 -t 4 \
  -f lavfi -i sine=frequency=440:duration=4 -c:v libx264 -preset veryfast \
  -c:a aac -shortest /tmp/pc_test.mp4
ffmpeg -y -loglevel error -i /tmp/pc_test.mp4 -vn -c:a copy /tmp/pc_test.m4a
"${ADB[@]}" push /tmp/pc_test.mp4 /sdcard/Download/pc_test.mp4 >/dev/null
"${ADB[@]}" push /tmp/pc_test.m4a /sdcard/Download/pc_test.m4a >/dev/null

echo "== sidecar"
LIST=$(curl -s -m 30 -H "X-PhoneClip-Token: $TOKEN" "$BASE/v1/sidecar/probe?path=/sdcard/Download")
echo "$LIST" | grep -q "pc_test.mp4" && ok "list /sdcard/Download" || no "list: $LIST"
GET=$(curl -s -m 60 -H "X-PhoneClip-Token: $TOKEN" "$BASE/v1/sidecar/get?path=/sdcard/Download/pc_test.m4a")
rm -f "$HOME/Downloads/PhoneClip/pc_test.m4a"
GET=$(curl -s -m 60 -H "X-PhoneClip-Token: $TOKEN" "$BASE/v1/sidecar/get?path=/sdcard/Download/pc_test.m4a")
[ -f "$HOME/Downloads/PhoneClip/pc_test.m4a" ] && ok "download pc_test.m4a" || no "download: $GET"

echo "== transcription (mock endpoint)"
cat > /tmp/mock_transcribe.py <<'PY'
import http.server, json
class H(http.server.BaseHTTPRequestHandler):
    def do_POST(self):
        n = int(self.headers.get('Content-Length') or 0)
        self.rfile.read(n or 0)
        body = json.dumps({"text": "mock transcript from selftest"}).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)
    def log_message(self, *a): pass
http.server.HTTPServer(("0.0.0.0", 47600), H).serve_forever()
PY
lsof -nP -iTCP:$PORT -sTCP:LISTEN >/dev/null 2>&1 && { echo "port $PORT busy"; exit 1; }
python3 /tmp/mock_transcribe.py > /tmp/mock_transcribe.log 2>&1 &
MOCK=$!
trap 'kill $MOCK 2>/dev/null || true' EXIT
sleep 1
curl -s -m 3 -X POST --data-binary x http://127.0.0.1:$PORT/v1/audio/transcriptions | grep -q mock \
  || { echo "mock transcription server not answering locally"; exit 1; }
if [ "$(reachable "$PHONE_IP")" = "0" ]; then
  "${ADB[@]}" shell am start -n $PKG/.MainActivity \
    --es tbase "http://$PHONE_IP:$PORT" --es tkey mock --es tmodel mock --ez tsync true >/dev/null
fi
rm -f "$TDIR"/*-pc_test.m4a.txt "$TDIR/pc_test.m4a.txt"
"${ADB[@]}" shell am broadcast -n $PKG/.CmdReceiver --es token "$TOKEN" \
  --es action transcribetest --es path /sdcard/Download/pc_test.m4a >/dev/null
TRANSCRIPT=""
for i in $(seq 1 30); do
  TRANSCRIPT=$(ls -t "$TDIR"/*-pc_test.m4a.txt 2>/dev/null | head -1 || true)
  [ -n "$TRANSCRIPT" ] && break
  sleep 1
done
if [ -n "$TRANSCRIPT" ] && grep -q "mock transcript" "$TRANSCRIPT"; then
  ok "transcription + sync"
else
  no "transcription (see logcat PhoneClip)"
fi

echo "== 720p compression"
rm -f "$HOME/Downloads/PhoneClip/pc_test-720p.mp4"
"${ADB[@]}" shell am broadcast -n $PKG/.CmdReceiver --es token "$TOKEN" \
  --es action compresstest --es path /sdcard/Download/pc_test.mp4 >/dev/null
for i in $(seq 1 120); do
  [ -f "$HOME/Downloads/PhoneClip/pc_test-720p.mp4" ] && break
  sleep 1
done
if [ -f "$HOME/Downloads/PhoneClip/pc_test-720p.mp4" ]; then
  W=$(ffprobe -v error -select_streams v:0 -show_entries stream=width -of csv=p=0 \
      "$HOME/Downloads/PhoneClip/pc_test-720p.mp4" | head -1 || true)
  [ "$W" = "1280" ] && ok "compressed width=$W" || no "compressed width=$W"
else
  no "compression output missing"
fi

echo "== audio compression (AAC 96k)"
rm -f "$HOME/Downloads/PhoneClip/pc_test-96k.m4a"
"${ADB[@]}" shell am broadcast -n $PKG/.CmdReceiver --es token "$TOKEN" \
  --es action compressaudiotest --es path /sdcard/Download/pc_test.m4a >/dev/null
for i in $(seq 1 60); do
  [ -f "$HOME/Downloads/PhoneClip/pc_test-96k.m4a" ] && break
  sleep 1
done
if [ -f "$HOME/Downloads/PhoneClip/pc_test-96k.m4a" ]; then
  BR=$(ffprobe -v error -select_streams a:0 -show_entries stream=bit_rate -of csv=p=0 \
       "$HOME/Downloads/PhoneClip/pc_test-96k.m4a" | head -1 || true)
  [ -n "$BR" ] && [ "$BR" -le 120000 ] && ok "audio bitrate=$BR" || no "audio bitrate=$BR"
else
  no "audio compression output missing"
fi

echo
echo "RESULT: $pass passed, $fail failed"
[ "$fail" = "0" ]
