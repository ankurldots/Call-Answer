#!/usr/bin/env bash
# Downloads and verifies the models bundled in the APK assets. Run from anywhere.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
A="$ROOT/archive-ondevice/src/main/assets/models"
T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
mkdir -p "$A/asr" "$A/vad"

fetch() { # url file sha256
  curl -fsSL "$1" -o "$T/$2"
  echo "$3  $T/$2" | shasum -a 256 -c - >/dev/null || { echo "SHA-256 mismatch: $2" >&2; exit 1; }
}

fetch https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx silero_vad.onnx \
  9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6
cp "$T/silero_vad.onnx" "$A/vad/silero_vad.onnx"

install_vosk() { # zipname dir sha
  fetch "https://alphacephei.com/vosk/models/$1.zip" "$1.zip" "$3"
  unzip -q "$T/$1.zip" -d "$T"
  rm -rf "$A/asr/$2"; mv "$T/$1" "$A/asr/$2"
}
install_vosk vosk-model-small-hi-0.22 vosk-small-hi 7c50a10866889f0ac21d912c20537a055a597ed09fc1d3e5bcd798f9f0017e48
install_vosk vosk-model-small-en-in-0.4 vosk-small-en-in 20663dcac4d5cb783a579c54d98339344a688e4ec6e1b4a4b059fd1235454cc7
echo "Models installed under $A"
