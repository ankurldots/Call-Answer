# archive-ondevice

Archived on-device call audio path. **Not shipped and not compiled.** `:app` does not depend on this module, and the
module applies only the Gradle `base` plugin, so a build never compiles these sources.

Why it was archived: on the target phone (Samsung Galaxy M34 5G) Android silences call-time microphone capture for
normal apps, only an accessibility service can capture (and UPI apps refuse to run while one is on), and the phone's
echo cancellation removes the Agent's loudspeaker audio. The cloud Agent (`.kiro/specs/cloud-call-agent/`) replaces it.

The last commit that contained this code inside `:app` is tagged `archive/ondevice-audio`.

## Contents

| Path | What it was |
|---|---|
| `src/main/kotlin/com/callanswer/app/a11y` | Accessibility service capture and the `WRITE_SECURE_SETTINGS` toggle |
| `.../audio`, `.../asr`, `.../tts`, `.../translate`, `.../cloudtts`, `.../nlg` | Capture, VAD, on-device speech recognition and synthesis, Polly cache, cloud LLM client |
| `.../telecom` | Call screening service that auto-answered calls |
| `.../service` | Call pipeline, foreground service, overlay, live call screen, notifications, outbox worker |
| `.../recording` | Recording folder linking and playback |
| `.../sms` | On-device Map_SMS and silent spam-report send |
| `src/main/assets` | Bundled ASR/VAD/TTS models and fallback phrase WAVs |
| `src/main/res/xml` | Accessibility service configuration |
| `src/test`, `src/androidTest` | Tests of the archived code |

The code imports classes that still live in `:app` (`CallLog`, `data/`, `auth/`, `ui/`) and string resources, so it
does not build on its own. To revive it, check out the `archive/ondevice-audio` tag instead.

Notes for reading it: the sherpa-onnx AAR (git-ignored) was `app/libs/sherpa-onnx-2.0.2.aar`; models are fetched with
`tools/fetch_models.sh` into `src/main/assets/models`.
