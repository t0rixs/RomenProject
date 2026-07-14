#!/usr/bin/env bash
# RomenMobile のデバッグログを監視する
set -euo pipefail
serial="${1:-AUBILBMVYH4LIJDQ}"
recording="${2:-}"
echo "監視端末: $serial"
adb -s "$serial" logcat -c
adb -s "$serial" shell am force-stop com.example.romenmobile
if [ -n "$recording" ]; then
  echo "記録を自動オープン: $recording"
  adb -s "$serial" shell am start -n com.example.romenmobile/.MainActivity --es open_recording "$recording"
else
  adb -s "$serial" shell am start -n com.example.romenmobile/.MainActivity
fi
echo "--- RomenMobileDbg / crash ログ (Ctrl+Cで終了) ---"
adb -s "$serial" logcat -v time RomenMobileDbg:I AndroidRuntime:E libc:F DEBUG:E ActivityManager:I *:S
