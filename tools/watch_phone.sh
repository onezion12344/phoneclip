#!/bin/bash
# Wait up to 4 hours for the Ace to become adb-visible, then run the device self-test.
# Usage: tools/watch_phone.sh [serial]
LOG=/tmp/pc-watch.log
: > "$LOG"
echo "watch start $(date)" >> "$LOG"
START=$SECONDS
while [ $((SECONDS - START)) -lt 14400 ]; do
  for s in $(adb devices | awk 'NR>1 && $2=="device"{print $1}'); do
    m=$(adb -s "$s" shell getprop ro.product.model 2>/dev/null | tr -d '\r')
    if [ "$m" = "PJX110" ] || [ "$m" = "CPH2551" ]; then
      echo "FOUND $s $m $(date)" >> "$LOG"
      cd "$(dirname "$0")/.." || exit 1
      ./selftest.sh "$s" >> "$LOG" 2>&1
      echo "SELFTEST_EXIT=$? $(date)" >> "$LOG"
      exit 0
    fi
  done
  timeout 6 adb connect 100.76.95.13:5555 >/dev/null 2>&1 || true
  timeout 6 adb connect 100.89.113.30:5555 >/dev/null 2>&1 || true
  for svc in $(timeout 6 adb mdns services 2>/dev/null | awk '/_adb-tls-connect\._tcp/{print $1}'); do
    timeout 6 adb connect "${svc}._adb-tls-connect._tcp" >/dev/null 2>&1 || true
  done
  sleep 45
done
echo "TIMEOUT $(date)" >> "$LOG"
