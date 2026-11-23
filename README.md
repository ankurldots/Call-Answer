# Call-Answer
A call-answering AI agent for people in India. Calls you don't answer are forwarded to a cloud Agent that talks to the caller in Hindi or English, gives delivery directions, keeps sales callers busy, and lets you take over from an Android companion app. Anyone can run it with their own [Exotel](https://exotel.com) account and AWS account. One deployment serves several users.

## What it does
When you don't pick up (or are busy or unreachable), your carrier forwards the call to your Exotel number. The Agent answers, greets the caller, and works out what they want:

| Caller intent | What the Agent does |
|---|---|
| **Delivery** | Gives directions to the house, starting with which colony gate to use, and can send the map by SMS. |
| **Door not answered** | Says "please wait" and alerts you in the app; **Take over** connects the caller to your mobile. |
| **Solicitation** (loan, property, other sales pitches) | Keeps the caller busy with fictitious, plausible-sounding replies. No real personal, financial or location data is used. |
| **Unknown** | Asks the reason, then takes a message. |
| **Repeat caller** | If the same number called within a few minutes (default 15), the call rings through to your mobile instead of the Agent, with a ringing alert in the app. The window and ring time are configurable. |

- **Languages:** Hindi and English (Indian), replying in the caller's language, with a female or male voice.
- **Directions:** Gate names, open times, the map location and the delivery steps are settings you configure for your own address. The defaults (Gate 3 from 22:30 to 05:30, Gate 1 otherwise) come from the original household and are only examples. Gate info and directions come from server code, not from the AI model.
- **Companion app:** live alert with Take over, ringing alert for repeat callers, live transcript, call log with cost, settings (gates, ring-through, SMS SIM), and spam reports to 1909 (TRAI).

## Status and scope
This project started for one household and is now meant for anyone to run. It is being re-architected.

- **Current direction (cloud Agent):** carrier call forwarding (tested with Airtel) to your [Exotel](https://exotel.com) number, Exotel AgentStream streaming the call audio over a WebSocket to an Agent service in AWS Tokyo (`ap-northeast-1`), and [Amazon Nova 2 Sonic](https://docs.aws.amazon.com/nova/latest/nova2-userguide/using-conversational-speech.html) as the speech-to-speech model. Call audio is processed in Tokyo, outside India. The spec is in `.kiro/specs/cloud-call-agent/`. Spikes are tracked in `docs/cloud-spikes.md`; the task list is in `.kiro/specs/cloud-call-agent/tasks.md`.
- **Why the pivot:** on the target phone (Samsung Galaxy M34 5G) an on-device Agent can't work. Android silences call-time microphone capture for normal apps (only an accessibility service could capture, and UPI apps refuse to run while one is on), and the phone's echo cancellation removes the Agent's loudspeaker audio, so the caller never hears it. The original on-device spec is kept as history in `.kiro/specs/ai-call-screening-agent/`.

> Consider local laws on call recording and on automated call answering. The cloud Agent plays a spoken recording notice and can disclose that it is an AI.

## Project layout

| Module / folder | Purpose |
|---|---|
| `app` | Android companion app (`com.callanswer.app`, minSdk 29, targetSdk 36). It answers no calls, captures no call audio and never enables an accessibility service. |
| `archive-ondevice` | Archived on-device call audio path (accessibility capture, on-device speech models, call screening answering, overlay). **Archived and not shipped:** `:app` does not depend on it and Gradle does not compile it. The last commit that held this code in `:app` is tagged `archive/ondevice-audio`. See `archive-ondevice/ARCHIVE.md`. |
| `core` | Shared call-handling rules (gates, directions, PII guard, spam report, call log, route decisions). JVM tests run without a device. Used by `app` and `agent`. |
| `agent` | Cloud Agent service (JVM, runs as a container on ECS Fargate): Exotel AgentStream WebSocket, Nova 2 Sonic sessions, tools, ring-through. Has a `Dockerfile`. |
| `infra/cloud-agent` | AWS SAM/CloudFormation template for the Agent (network, ALB, certificate, ECS service). DNS is on Cloudflare; see `docs/cloud/dns.md`. |
| `scripts` | `build-push-agent.sh` (build and push the Agent image), `check-16kb.sh` (16 KB page-size check for the app), `admin/` |
| `spike-cloud` | JVM harness for the cloud spikes (results in `spike-cloud/results/`, git-ignored) |
| `spike` | On-device feasibility spikes (history; see `docs/spike-s1-s3-runbook.md`) |
| `lambda/polly_tts` | AWS Lambda for Polly text-to-speech (see its README) |
| `tools` | Scripts for models, voices, traces and test suites |
| `docs` | Spike runbooks and results, E2E scenarios, network test matrix |

Cloud setup notes (Cognito, DNS, Bedrock access) are in `docs/cloud/`. Progress on the Agent and its infrastructure is tracked in `.kiro/specs/cloud-call-agent/tasks.md`.

## What you need to run your own
- An Exotel account with one Exotel number (ExoPhone) per user, and call forwarding from each user's mobile carrier to that user's number. The Agent identifies the user from the Exotel number the call arrived on, so two users cannot share one number. The Agent speaks Exotel's AgentStream protocol; another telephony service would need new code.
- An AWS account with Amazon Bedrock access to Nova 2 Sonic in `ap-northeast-1`, and a domain name for the Agent endpoint. The reference setup uses `agent.leadingdots.com`; replace it with your own via the template parameters in `infra/cloud-agent/template.yaml`.
- A Cognito user pool for app sign-in (see `docs/cloud/cognito.md`). Each user signs in to the app with their own account and sees only their own settings, call log and recordings.
- An admin (the deployment owner) who creates each user and assigns their Exotel number by hand with `scripts/admin/callagent-admin`. There is no self-service sign-up or admin screen.
- An Android phone (Android 10 or newer) for the companion app, which you build and sideload.

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
./gradlew :core:test :agent:test :app:testDebugUnitTest :spike-cloud:test
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
