# S7: on-device AI benchmark

Menu: `S7 On-device AI benchmark`. Results go to the shared spike log with tag `[S7]`; lines starting with `RESULT |` form the table, `RULE |` lines give the Whisper go/no-go check (end-to-end p90 at most 2.5 s and intent accuracy at least 90%, both cold and sustained).

## One-time build setup

The sherpa-onnx AAR is not on Maven and is git-ignored. Download it into `spike/libs/`:

    curl -L -o spike/libs/sherpa-onnx-2.0.2.aar \
      https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.2/sherpa-onnx-2.0.2.aar

Vosk (`com.alphacephei:vosk-android`) comes from Maven Central. The build is arm64-v8a only.

## Models (not bundled): where to put them on the device

Folder: `/sdcard/Android/data/com.callanswer.spike/files/s7-models/` (the activity prints the exact path).
Push with `adb push <local> /sdcard/Android/data/com.callanswer.spike/files/s7-models/`.

| Folder or file | Contents | Source |
| --- | --- | --- |
| `whisper-base/` | `base-encoder.int8.onnx`, `base-decoder.int8.onnx`, `base-tokens.txt` | sherpa-onnx release `asr-models`: `sherpa-onnx-whisper-base` (use the int8 files) |
| `vosk-model-small-hi-0.22/` | unpacked Vosk model | alphacephei.com/vosk/models |
| `vosk-model-small-en-in-0.4/` | unpacked Vosk model | alphacephei.com/vosk/models |
| `silero_vad.onnx` | Silero VAD | sherpa-onnx release `asr-models`: `silero_vad.onnx` |
| `piper-en/` | one `*.onnx`, `tokens.txt`, `espeak-ng-data/` | sherpa-onnx release `tts-models`: any `vits-piper-en_*` (an en_IN or en_GB voice if available) |
| `piper-hi/` | same layout | sherpa-onnx `vits-piper-hi_*` model if available; if missing, Hindi Piper latency is reported as n/a |

Folder names are matched by prefix (`whisper-base*`, `vosk-model-small-hi*`, `vosk-model-small-en-in*`, `piper-en*`, `piper-hi*`).

## What is measured

- Corpus: 30 Caller utterances (10 Hindi, 10 English, 10 Hinglish) in `src/main/assets/s7/wav`, with `reference.tsv` (id, lang, polly_lang, expected_intent, text). Generated with `tools/generate_polly_voices.sh` (Polly Kajal, neural, `ap-south-1`, AWS profile `ldots`, 16 kHz mono PCM in WAV).
- Pipeline per utterance: Silero VAD (threshold 0.5, 0.5 s min silence) over the utterance plus 1.2 s of silence, ASR, draft phrase table (`S7Metrics`), Piper first-audio callback.
- End-to-end latency = VAD endpoint delay (audio time from last speech sample to endpoint) + ASR tail after the endpoint + classify + Piper first audio.
  - Whisper: tail is the full decode of the VAD speech (2 threads, language auto-detect).
  - Vosk: audio is fed in 100 ms chunks up to the endpoint; tail is the last chunk plus final result.
- WER is word level on normalized text (NFKC, lower case, nukta removed, punctuation stripped), overall and per language. Script mismatches (for example Whisper writing Hinglish in Latin where the reference is mixed script) inflate Hinglish WER; intent accuracy is the more reliable number.
- Cold: engines freshly loaded, each utterance once. Sustained: the corpus loops for 10 minutes per engine; the log gives p50/p90 for the whole loop, the first loop and the last 2 minutes (thermal throttling).
- Peak PSS: `Debug.MemoryInfo.totalPss` sampled every 300 ms and after each utterance, for the whole run.
- Big-core affinity: the bench thread is pinned with `taskset -p` before the models load, so ORT threads inherit it. The log records whether the OS allowed it.

## Caveat

The audio is Polly TTS, which is cleaner than a loudspeaker call heard through the S2 capture path. It flatters the recognizers and the intent accuracy. Add real recorded speech (S2 dumps) with their transcripts to `reference.tsv` and `wav/` before relying on the numbers.
