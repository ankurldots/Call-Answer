#!/usr/bin/env bash
# Check 16 KB page-size compatibility of an APK: zipalign (stored .so files aligned to 16 KB)
# and ELF LOAD segment alignment (>= 0x4000) of every native library.
#
# Usage: scripts/check-16kb.sh [path/to/app.apk]
# Default APK: app/build/outputs/apk/release/app-release*.apk (falls back to the debug APK).
# Tools: ANDROID_HOME/ANDROID_SDK_ROOT (default ~/Library/Android/sdk) for zipalign (build-tools)
# and llvm-readelf (ndk); a readelf on PATH is used if present.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"

APK="${1:-}"
if [ -z "$APK" ]; then
  APK="$(ls "$ROOT"/app/build/outputs/apk/release/*.apk 2>/dev/null | head -n1 || true)"
  [ -n "$APK" ] || APK="$(ls "$ROOT"/app/build/outputs/apk/debug/*.apk 2>/dev/null | head -n1 || true)"
fi
[ -f "${APK:-}" ] || { echo "APK not found; build it first or pass its path" >&2; exit 2; }

ZIPALIGN="$(ls -d "$SDK"/build-tools/*/zipalign 2>/dev/null | sort -V | tail -n1 || true)"
[ -n "$ZIPALIGN" ] || ZIPALIGN="$(command -v zipalign || true)"
[ -n "$ZIPALIGN" ] || { echo "zipalign not found" >&2; exit 2; }

READELF="$(command -v llvm-readelf || command -v readelf || true)"
[ -n "$READELF" ] || READELF="$(ls -d "$SDK"/ndk/*/toolchains/llvm/prebuilt/*/bin/llvm-readelf 2>/dev/null | sort -V | tail -n1 || true)"
[ -n "$READELF" ] || { echo "readelf not found" >&2; exit 2; }

echo "APK: $APK"
FAIL=0

echo "== zipalign (16 KB) =="
if "$ZIPALIGN" -c -P 16 -v 4 "$APK" >/tmp/check-16kb-zipalign.$$ 2>&1; then
  echo "OK"
else
  grep -i "\.so" /tmp/check-16kb-zipalign.$$ | grep -iv "(OK" || tail -n 5 /tmp/check-16kb-zipalign.$$
  echo "FAIL: zipalign reports misaligned entries"
  FAIL=1
fi
rm -f /tmp/check-16kb-zipalign.$$

echo "== ELF LOAD alignment (>= 0x4000) =="
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
unzip -q -o "$APK" 'lib/*' -d "$TMP" 2>/dev/null || true
COUNT=0
while IFS= read -r so; do
  abi="$(basename "$(dirname "$so")")"
  case "$abi" in arm64-v8a|x86_64) ;; *) continue ;; esac   # 16 KB only matters for 64-bit ABIs
  COUNT=$((COUNT + 1))
  ALIGNS="$("$READELF" -lW "$so" | awk '$1=="LOAD" { print $NF }')"
  if [ -z "$ALIGNS" ]; then
    echo "FAIL: no LOAD segments in ${so#$TMP/}"; FAIL=1; continue
  fi
  BAD=""
  for a in $ALIGNS; do
    if [ $((a)) -lt 16384 ]; then BAD="$BAD $a"; fi
  done
  if [ -n "$BAD" ]; then
    echo "FAIL: ${so#$TMP/} has LOAD alignment $BAD"
    FAIL=1
  else
    echo "OK:   ${so#$TMP/}"
  fi
done < <(find "$TMP/lib" -name '*.so' 2>/dev/null | sort)
echo "Checked $COUNT 64-bit native libraries"

[ "$FAIL" -eq 0 ] && echo "16 KB check passed" || { echo "16 KB check FAILED"; exit 1; }
