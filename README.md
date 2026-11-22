# Call-Answer
An Android app (Kotlin) that answers incoming calls from unknown numbers with an AI voice agent, so the phone owner doesn't have to pick up spam and delivery calls.

## What it does
When a number that is not in your contacts calls, the Agent answers on speakerphone, says "Hello", listens, and classifies what the caller wants:

| Caller intent | What the Agent does |
|---|---|
| **Delivery** | Gives directions to the house, starting with which colony gate to use. |
| **Door not answered** | Transfers the call to a real person (a number you configure). |
| **Solicitation** (loan, property, other sales pitches) | Keeps the caller busy with fictitious, plausible-sounding information until they hang up. No real personal, financial or location data is used. |
| **Unknown** | Re-prompts after a silence timeout (default 8 s). |

- **Languages:** Hindi and English. The Agent matches the caller's language.
- **Gate info:** The Agent tells callers which colony gate to use. By default, only Gate 3 is open from 22:30 to 05:30, and Gate 1 is used at other times. Gates, times and delivery steps are configurable.
- **Works on bad networks:** Speech recognition (Vosk, Silero VAD via sherpa-onnx), language detection and speech synthesis run on the device. Cloud text-to-speech (Amazon Polly through a small AWS Lambda API) is an optional enhancement.
- **Live call screen:** A notification and screen show the live transcript, detected intent and language, and controls to take over, mute, resume or end the call.
- **Call log:** Each screened call is stored with its transcript, intent, language and outcome. The app links it to the recording made by the phone's own call recorder. The app doesn't record calls itself.

## Status and scope
This is a personal project. The target device is a Samsung Galaxy M34 5G (One UI, India) with the Samsung Phone app as the default dialer. Android gives apps no direct access to cellular call audio, so the Agent speaks over the speakerphone and listens through the microphone, with echo handling to avoid treating its own voice as the caller's. Timing limits and on-device models are validated on that device only.

The requirements, design and task list are in `.kiro/specs/ai-call-screening-agent/`.

> Consider local laws on call recording and on automated call answering, and whether callers should be told they are speaking to an AI (the app has an optional spoken disclosure setting, off by default).

## Project layout

| Module / folder | Purpose |
|---|---|
| `app` | Android app (`com.callanswer.app`, minSdk 29, targetSdk 36) |
| `core` | Shared call-handling logic (JVM tests run without a device) |
| `spike` | Benchmark and feasibility spikes (see `spike/S7-README.md`) |
| `lambda/polly_tts` | AWS Lambda for Polly text-to-speech (see its README) |
| `tools` | Scripts for models, voices, traces and test suites |
| `docs` | E2E scenarios, network test matrix, spike runbooks |

## Running locally

### Prerequisites
- Android Studio (recent version with Android SDK 37.1 platform installed), or the command-line SDK.
- JDK 21 (Gradle's daemon toolchain is JetBrains JDK 21 and is downloaded automatically via foojay). The app compiles to Java 17.
- `local.properties` in the repo root pointing at your SDK (Android Studio creates it):
  ```
  sdk.dir=/path/to/Android/sdk
  ```
- An arm64 Android device (Android 10 / API 29 or newer) or emulator. Real call screening needs a physical device.
- `curl`, `unzip`, `shasum` for the model script.

### 1. Get the sherpa-onnx library
The AAR is git-ignored. If `app/libs/sherpa-onnx-2.0.2.aar` is missing:
```
mkdir -p app/libs
curl -L -o app/libs/sherpa-onnx-2.0.2.aar \
  https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.2/sherpa-onnx-2.0.2.aar
```

### 2. Download the speech models
Downloads the VAD and Vosk (Hindi and Indian English) models into `app/src/main/assets/models` and verifies SHA-256 checksums:
```
tools/fetch_models.sh
```

### 3. Build and install
```
./gradlew :app:assembleDebug
./gradlew :app:installDebug      # with a device connected (adb devices)
```
Or open the project in Android Studio and run the `app` configuration.

### 4. Grant permissions and set up call handling
On the device, open the app, grant the requested permissions, and set Call-Answer as the call screening app / default call handler when prompted (Settings > Apps > Default apps). Then call the phone from a number that is not in the contacts.

### Tests
```
./gradlew :core:test :app:testDebugUnitTest
```

### Debugging a call
Debug builds write a `trace-<sessionId>.tsv` file when a call ends. Pull the files with:
```
tools/e2e/pull_traces.sh
```
See `docs/e2e-scenarios.md` for the full manual test checklist.

### Optional: regenerate TTS voices
`tools/generate_polly_voices.sh` and `tools/generate_fallback_phrases.sh` use AWS Polly and need AWS credentials. See `lambda/polly_tts/README.md` for the Lambda.

## License

Copyright (c) 2026 LeadingDots. Licensed under the [PolyForm Noncommercial License 1.0.0](LICENSE). You can use, modify and share this project for personal, research, educational and other noncommercial purposes. Commercial use needs a separate license.

For commercial licensing or other questions, contact LeadingDots at [leadingdots.com](https://leadingdots.com) or [Ankur Gupta on LinkedIn](https://www.linkedin.com/in/ankurgupta10/).
