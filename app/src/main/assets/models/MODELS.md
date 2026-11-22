# Bundled models

Binaries are gitignored. Run `tools/fetch_models.sh` (downloads and verifies SHA-256) before building.
Whisper and Piper voices are not bundled.

| Asset path | Source | Version | Size | SHA-256 (of download) | License |
|---|---|---|---|---|---|
| `vad/silero_vad.onnx` | https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx (Silero VAD from snakers4/silero-vad, as packaged for sherpa-onnx) | release `asr-models`, file dated 2024-11 | 643,854 B | `9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6` | MIT |
| `asr/vosk-small-hi/` | https://alphacephei.com/vosk/models/vosk-model-small-hi-0.22.zip | vosk-model-small-hi-0.22 | 44,458,845 B zip (~66 MB unpacked) | `7c50a10866889f0ac21d912c20537a055a597ed09fc1d3e5bcd798f9f0017e48` | Apache 2.0 |
| `asr/vosk-small-en-in/` | https://alphacephei.com/vosk/models/vosk-model-small-en-in-0.4.zip | vosk-model-small-en-in-0.4 | 37,573,330 B zip (~66 MB unpacked) | `20663dcac4d5cb783a579c54d98339344a688e4ec6e1b4a4b059fd1235454cc7` | Apache 2.0 |

Vosk folder layout: `am/`, `conf/`, `graph/`, `ivector/`, `README` (these versions have no `uuid` file). Folders are renamed to match `AsrModelStore`.
