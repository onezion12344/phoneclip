#!/bin/bash
# Device-level self-test on an Android emulator (arm64, Android 34).
# This DOWNLOADS ~4-5 GB (emulator + system image + AVD) — it refuses to run
# unless you pass --yes. Disk must have at least 8 GB free.
#
# Usage: tools/emulator_selftest.sh --yes [--keep]
set -euo pipefail

SDK=/opt/homebrew/share/android-commandlinetools
export ANDROID_SDK_ROOT="$SDK"
export ANDROID_HOME="$SDK"
AVD=phoneclip_test
IMG="system-images;android-34;google_apis;arm64-v8a"
HERE="$(cd "$(dirname "$0")/.." && pwd)"

if [ "${1:-}" != "--yes" ]; then
  free=$(df -g / | awk 'NR==2{print $4}')
  echo "This installs the Android emulator + $IMG (~4-5 GB)."
  echo "Free disk: ${free} GB (need >= 8 GB)."
  echo "Re-run with --yes to proceed."
  exit 1
fi

free=$(df -g / | awk 'NR==2{print $4}')
if [ "$free" -lt 8 ]; then
  echo "only ${free} GB free; need 8 GB. Free space first."
  exit 1
fi

echo "==> installing packages (this takes a while)"
yes | sdkmanager --sdk_root="$SDK" --licenses >/dev/null 2>&1 || true
sdkmanager --sdk_root="$SDK" "emulator" "$IMG" "platforms;android-34" >/dev/null

echo "==> creating AVD $AVD"
echo no | avdmanager create avd -n "$AVD" -k "$IMG" -d pixel_6 --force >/dev/null
CFG="$HOME/.android/avd/$AVD.avd/config.ini"
sed -i '' 's/^hw.ramSize=.*/hw.ramSize=2048/' "$CFG" 2>/dev/null || echo 'hw.ramSize=2048' >> "$CFG"
grep -q '^disk.dataPartition.size=' "$CFG" 2>/dev/null || echo 'disk.dataPartition.size=4096M' >> "$CFG"

echo "==> booting headless"
"$SDK/emulator/emulator" -avd "$AVD" -no-window -no-audio -no-boot-anim \
  -gpu swiftshader_indirect -no-snapshot -wipe-data -memory 2048 \
  > /tmp/phoneclip-emulator.log 2>&1 &
EPID=$!
cleanup() {
  kill "$EPID" 2>/dev/null || true
  adb -s emulator-5554 emu kill >/dev/null 2>&1 || true
}
trap cleanup EXIT

adb wait-for-device
for i in $(seq 1 100); do
  boot=$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')
  [ "$boot" = "1" ] && break
  sleep 3
done
[ "$boot" = "1" ] || { echo "emulator did not boot; see /tmp/phoneclip-emulator.log"; exit 1; }

SERIAL=$(adb devices | awk 'NR>1 && $2=="device"{print $1; exit}')
echo "==> booted $SERIAL; running the device self-test against 10.0.2.2"

SERVER_URL="http://10.0.2.2:47411" "$HERE/selftest.sh" "$SERIAL"
rc=$?
if [ "${2:-}" != "--keep" ]; then
  cleanup
  trap - EXIT
fi
exit $rc
