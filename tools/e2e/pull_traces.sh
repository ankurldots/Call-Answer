#!/usr/bin/env bash
# Pulls the call traces written by debug builds (task 34.3) into ./traces-out.
set -euo pipefail
PKG="${1:-com.callanswer.app}"
OUT="${2:-traces-out}"
mkdir -p "$OUT"
adb pull "/sdcard/Android/data/$PKG/files/traces/" "$OUT/"
echo "Traces in $OUT/traces"
