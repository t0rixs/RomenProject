#!/usr/bin/env bash
set -euo pipefail

mode="${1:-run}"
mapfile_cmd() {
  adb devices | awk 'NR > 1 && $2 == "device" { print $1 }'
}

phones=()
while IFS= read -r serial; do
  [ -n "$serial" ] || continue
  model="$(adb -s "$serial" shell getprop ro.product.model 2>/dev/null | tr -d '\r')"
  case "$model" in
    *THINKLET*|*Thinklet*|*thinklet*) ;;
    *) phones+=("$serial") ;;
  esac
done < <(mapfile_cmd)

if [ "${#phones[@]}" -eq 0 ]; then
  echo "Androidスマホが見つかりません。USBデバッグを有効にして接続してください。" >&2
  exit 1
fi
if [ "${#phones[@]}" -gt 1 ]; then
  echo "スマホが複数接続されています。1台だけ接続してください。" >&2
  exit 1
fi

serial="${phones[0]}"
echo "スマホを自動選択: $(adb -s "$serial" shell getprop ro.product.model | tr -d '\r')"
if [ "$mode" != "run-only" ]; then
  adb -s "$serial" install -r RomenMobile/app/build/outputs/apk/debug/app-debug.apk
fi

if [ "$mode" != "install" ]; then
  adb -s "$serial" shell am force-stop com.example.romenmobile
  adb -s "$serial" shell am start -n com.example.romenmobile/.MainActivity
fi
