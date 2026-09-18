#!/usr/bin/env bash
set -uo pipefail
# Capture before the emulator action tears its virtual device down.
result=0
gradle --no-daemon :app:connectedDebugAndroidTest || result=$?
adb pull /data/local/tmp/pitwall-screenshots screenshots || true
exit "$result"
