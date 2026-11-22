#!/usr/bin/env bash
# Generates Caller utterance WAVs (16 kHz mono PCM16) with Amazon Polly voice Kajal (neural engine).
# Credentials come from the AWS CLI profile (default "ldots"); nothing is stored in this repo.
#
# Usage: tools/generate_polly_voices.sh [reference.tsv] [out_dir]
#   reference.tsv columns (tab separated, header row): id, lang, polly_lang, expected_intent, text
#   Defaults generate the S7 benchmark set into spike/src/main/assets/s7/wav.
# Env: AWS_PROFILE_NAME (default ldots), POLLY_REGION (default ap-south-1).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
REF="${1:-$ROOT/spike/src/main/assets/s7/reference.tsv}"
OUT="${2:-$ROOT/spike/src/main/assets/s7/wav}"
PROFILE="${AWS_PROFILE_NAME:-ldots}"
REGION="${POLLY_REGION:-ap-south-1}"

mkdir -p "$OUT"
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT

# Fail early with the exact CLI error if Kajal neural is not reachable.
aws polly describe-voices --profile "$PROFILE" --region "$REGION" --engine neural \
  --query "Voices[?Id=='Kajal'].Id" --output text | grep -q Kajal

tail -n +2 "$REF" | while IFS=$'\t' read -r id lang polly_lang intent text; do
  [ -z "$id" ] && continue
  aws polly synthesize-speech --profile "$PROFILE" --region "$REGION" \
    --engine neural --voice-id Kajal --language-code "$polly_lang" \
    --output-format pcm --sample-rate 16000 --text-type text --text "$text" \
    "$tmp/$id.pcm" >/dev/null
  python3 - "$tmp/$id.pcm" "$OUT/$id.wav" <<'PY'
import sys, wave
pcm = open(sys.argv[1], "rb").read()
with wave.open(sys.argv[2], "wb") as w:
    w.setnchannels(1); w.setsampwidth(2); w.setframerate(16000); w.writeframes(pcm)
PY
  echo "wrote $OUT/$id.wav ($polly_lang)"
done
