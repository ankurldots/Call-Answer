#!/usr/bin/env bash
# Renders the fallback phrase WAVs into app/src/main/assets/fallback/ with Amazon Polly (Kajal, neural).
# Files: <lang>_<gender>_<NN>.wav, NN = 01 fallback, 02 filler, 03 please wait, 04 nobody available.
# Polly has no Indian-English or Hindi male voice, so only the female set is generated here. The male
# set must come from another source (drop <lang>_male_NN.wav files into the same folder).
# Credentials come from the AWS CLI profile (default "ldots"); nothing is stored in this repo.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/app/src/main/assets/fallback"
python3 "$ROOT/tools/generate_polly_voices.py" "$ROOT/tools/sentences/fallback/en.txt" "$OUT" --language-code en-IN --prefix en_female
python3 "$ROOT/tools/generate_polly_voices.py" "$ROOT/tools/sentences/fallback/hi.txt" "$OUT" --language-code hi-IN --prefix hi_female
