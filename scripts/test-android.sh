#!/usr/bin/env bash
set -uo pipefail
# Capture before the emulator action tears its virtual device down.
module="${1:-app}"
case "$module" in
  app) expected=(01-record 02-settings 03-light-settings 04-lap-sheet 05-fullscreen 06-sessions 07-timeline 08-details 09-recording) ;;
  wear) expected=(01-ready 02-sensors 03-recording-connected 04-recording-offline 05-pending 06-recorder-error 07-results 08-results-warning) ;;
  *) echo "Expected app or wear" >&2; exit 2 ;;
esac
result=0
adb shell rm -rf /data/local/tmp/pitwall-screenshots || exit 1
gradle --no-daemon ":$module:connectedDebugAndroidTest" || result=$?
if (( result != 0 )); then
  adb shell mkdir -p /data/local/tmp/pitwall-screenshots
  adb shell screencap -p /data/local/tmp/pitwall-screenshots/zz-runner-failure.png || true
fi
mkdir -p "screenshots/$module"
adb pull /data/local/tmp/pitwall-screenshots/. "screenshots/$module/" || result=1
adb logcat -d > "screenshots/$module/logcat.txt" || true
# A green test run must actually contain every expected capture.
for name in "${expected[@]}"; do
  if [[ ! -s "screenshots/$module/$name.png" ]]; then
    echo "Missing screenshot: $module/$name.png" >&2
    result=1
  fi
done
exit "$result"
