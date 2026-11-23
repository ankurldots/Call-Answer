# Call-Answer
A call-answering AI agent for one household in India. Calls from unknown numbers that you don't answer are forwarded to a cloud Agent that talks to the caller in Hindi or English, gives delivery directions, keeps sales callers busy, and lets you take over from an Android companion app.

## What it does
When you don't pick up (or are busy or unreachable), your carrier forwards the call to an Exotel number. The Agent answers, greets the caller, and works out what they want:

| Caller intent | What the Agent does |
|---|---|
| **Delivery** | Gives directions to the house, starting with which colony gate to use, and can send the map by SMS. |
| **Door not answered** | Says "please wait" and alerts you in the app; **Take over** connects the caller to your mobile. |
| **Solicitation** (loan, property, other sales pitches) | Keeps the caller busy with fictitious, plausible-sounding replies. No real personal, financial or location data is used. |
| **Unknown** | Asks the reason, then takes a message. |

- **Languages:** Hindi and English (Indian), replying in the caller's language, with a female or male voice.
- **Gate info:** By default only Gate 3 is open from 22:30 to 05:30, and Gate 1 at other times. Gates, times and delivery steps are configurable. Gate info and directions come from server code, not from the AI model.
- **Companion app:** live alert with Take over, live transcript, call log, settings, and spam reports to 1909 (TRAI).

## Status and scope
This is a personal project, being re-architected.

- **Current direction (cloud Agent):** carrier call forwarding (Airtel) to an [Exotel](https://exotel.com) number, Exotel AgentStream streaming the call audio over a WebSocket to an Agent service in AWS Tokyo (`ap-northeast-1`), and [Amazon Nova 2 Sonic](https://docs.aws.amazon.com/nova/latest/nova2-userguide/using-conversational-speech.html) as the speech-to-speech model. Call audio is processed in Tokyo, outside India. The spec is in `.kiro/specs/cloud-call-agent/`. Spikes are tracked in `docs/cloud-spikes.md`.
- **Why the pivot:** on the target phone (Samsung Galaxy M34 5G) an on-device Agent can't work. Android silences call-time microphone capture for normal apps (only an accessibility service could capture, and UPI apps refuse to run while one is on), and the phone's echo cancellation removes the Agent's loudspeaker audio, so the caller never hears it. The original on-device spec is kept as history in `.kiro/specs/ai-call-screening-agent/`.

> Consider local laws on call recording and on automated call answering. The cloud Agent plays a spoken recording notice and can disclose that it is an AI.

## Project layout

| Module / folder | Purpose |
|---|---|
| `app` | Android companion app (`com.callanswer.app`, minSdk 29, targetSdk 36). It answers no calls, captures no call audio and never enables an accessibility service. |
| `archive-ondevice` | Archived on-device call audio path (accessibility capture, on-device speech models, call screening answering, overlay). **Archived and not shipped:** `:app` does not depend on it and Gradle does not compile it. The last commit that held this code in `:app` is tagged `archive/ondevice-audio`. See `archive-ondevice/ARCHIVE.md`. |
| `core` | Shared call-handling rules (gates, directions, PII guard, spam report, call log). JVM tests run without a device. To be reused by the cloud Agent service. |
| `spike-cloud` | JVM harness for the cloud spikes (results in `spike-cloud/results/`, git-ignored) |
| `spike` | On-device feasibility spikes (history; see `docs/spike-s1-s3-runbook.md`) |
| `lambda/polly_tts` | AWS Lambda for Polly text-to-speech (see its README) |
| `tools` | Scripts for models, voices, traces and test suites |
| `docs` | Spike runbooks and results, E2E scenarios, network test matrix |

The cloud Agent service (`agent` module) and its infrastructure are planned in `.kiro/specs/cloud-call-agent/tasks.md`.

## Running locally

### Prerequisites
- Android Studio (recent version with Android SDK 37.1 platform installed), or the command-line SDK.
- JDK 21 (Gradle's daemon toolchain is JetBrains JDK 21 and is downloaded automatically via foojay). The app compiles to Java 17.
- `local.properties` in the repo root pointing at your SDK (Android Studio creates it):
  ```
  sdk.dir=/path/to/Android/sdk
  ```
- An arm64 Android device (Android 10 / API 29 or newer) or emulator. 

### 1. Build and install
```
./gradlew :app:assembleDebug
./gradlew :app:installDebug      # with a device connected (adb devices)
```
Or open the project in Android Studio and run the `app` configuration.

### 2. Sign in and allow notifications
On the device, open the app and follow onboarding (sign-in, then notifications). The app asks for the SMS permission (`SEND_SMS`) so that it can send the map to delivery callers from your own SIM; it needs no call or microphone permission. The app is sideloaded, not distributed through Google Play, so the Google Play SMS permission policy does not block `SEND_SMS`; the on-device call path is archived (see `archive-ondevice/ARCHIVE.md`).

### Tests
```
./gradlew :core:test :app:testDebugUnitTest :spike-cloud:test
```

### Debugging a call
The archived on-device build wrote `trace-<sessionId>.tsv` files when a call ended (see `archive-ondevice/`). Their helper:
```
tools/e2e/pull_traces.sh
```
See `docs/e2e-scenarios.md` for the full manual test checklist.

### Optional: regenerate TTS voices
`tools/generate_polly_voices.sh` and `tools/generate_fallback_phrases.sh` (the latter and `tools/fetch_models.sh` write into `archive-ondevice/`) use AWS Polly and need AWS credentials. See `lambda/polly_tts/README.md` for the Lambda.

## License

Copyright (c) 2026 LeadingDots. Licensed under the [PolyForm Noncommercial License 1.0.0](LICENSE). You can use, modify and share this project for personal, research, educational and other noncommercial purposes. Commercial use needs a separate license.

For commercial licensing or other questions, contact LeadingDots at [leadingdots.com](https://leadingdots.com) or [Ankur Gupta on LinkedIn](https://www.linkedin.com/in/ankurgupta10/).
