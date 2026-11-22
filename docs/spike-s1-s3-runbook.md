# Spike runbook: S0 to S3 on the Galaxy M34 5G

Steps are based on what the spike screens do. They have not been run on the phone yet,
so button labels may differ slightly.

## Setup (once)

1. Install the spike build: `./gradlew :spike:installDebug` (phone is on wireless adb).
2. Open the Spike app, tap **Request runtime permissions**, allow everything.
3. Tap **Request call screening role** and accept.
4. Tap **Battery: set to Unrestricted** and set the app to Unrestricted.
5. Tap **Full-screen intent access** and allow it.
   (There is no accessibility service in the spike or the app. UPI and banking apps refuse to work while one is on.)
6. Go back to the spike menu. Every line in the status list should read OK.
7. Have a second phone ready to call the M34, using a number that is not in the M34's contacts.

Tap **Clear log** before you start so the exported log only holds this run.

## S1 auto behaviour setting (do this before every test)
The spike menu (below the Setup buttons) and the S1 screen both have an **S1 auto behaviour**
selector. The menu status text and the S2/S3 banners show the current mode. The mode is logged
at every screened call (`active mode:` in tag S1).
- `AUTO_ANSWER_AND_END` (default): answers, then ends the call after 15, 60 or 180 s. Use for S1.
- `AUTO_ANSWER_ONLY`: answers, never ends. End the call from the second phone, the phone UI
  or the **End call now** button on S1. Use for S2 and S3.
- `OFF`: logs the call and starts the foreground service, but does not answer or end. Answer by hand.
First run: the 15 s auto-end hung up every call, which cut S2 and S3 short. S2/S3 show a red
banner while `AUTO_ANSWER_AND_END` is active.
## S0: device readout (1 minute)

- Open **S0**. It logs model, Android and One UI versions, RAM, carrier and similar.

## S1: screen, answer, foreground service, end call

1. Open **S1**, set mode `AUTO_ANSWER_AND_END`. Tap **Request phone and notification permissions** if it asks.
2. Do some runs with the screen off or locked and some with it unlocked.
3. Call the M34 from the second phone.
4. The app should screen the call, answer it and show the foreground-service notification.
   With mode `AUTO_ANSWER_AND_END` it ends the call by itself after the chosen delay (default 15 s).
5. Repeat 5 times, including at least one run with the screen locked.
6. Check the log for: ring-to-answer time, foreground-service start result,
   notification post time, and the `endCall()` result.
7. Pass: every call answered within about 2 s and the service starts without a
   `ForegroundServiceStartNotAllowedException`.
8. If the service start is refused, the app posts a full-screen notification instead.
   Note whether that fallback worked.

## S2: microphone capture
S2 answers the open question of the no-accessibility design: with a plain foreground service
(type `microphone`) as the capture owner and the speakerphone on, does `VOICE_RECOGNITION` or
`VOICE_COMMUNICATION` capture the Caller in-call? Earlier findings: `MIC` and `UNPROCESSED` were silenced in-call;
`VOICE_RECOGNITION` was not (that run used an accessibility service, so it must be repeated).
1. Set the S1 mode to `AUTO_ANSWER_ONLY` (or `OFF` and answer by hand). Open **S2**; the banner
   should be green.
2. Run once with no call, as a baseline (source: ALL sources).
3. Call the M34 from the second phone. Answer on **loudspeaker** (tap Speaker on the in-call screen).
4. Choose a **Source** (a single one, or ALL sources) and seconds per source (10 default, 30 option).
   Tick **Start capture when the call becomes OFFHOOK** and tap **Start** before calling, or leave
   it unticked and use a start delay. Then play audio into the M34 from the second phone
   (speak, or play a clip close to its mic) for the whole capture.
5. Do one call per source for `VOICE_RECOGNITION` and `VOICE_COMMUNICATION`. The foreground service
   owns the capture in every run.
6. Check the log for the `isClientSilenced` values and the RMS and dBFS per source.
   WAVs are saved in the app's `s2` folder.
7. Pass: at least one source records the second phone's voice at a clear level
   (RMS well above the baseline) and `isClientSilenced` is false.
   Note which source worked. If every source is silent, the Caller cannot be recognised without
   the default assistant role (see design.md, "Decision: no accessibility service").
## S2b: assistant-role capture
Question: does holding the default digital assistant role let the spike capture the Caller during a cellular call?
What Android documents ([Sharing audio input](https://developer.android.com/media/platform/sharing-audio-input),
[Concurrent capture](https://source.android.com/docs/core/audio/concurrent)): the call always gets the audio, and the
"voice call + ordinary app" rules name only accessibility services (and pre-installed apps with `CAPTURE_AUDIO_OUTPUT`)
as able to capture. The Assistant exemption is documented for "assistant + ordinary app", not for a live call, and
"privileged" means pre-installed. A user-installed spike holding the role is therefore not expected to be exempt.
This run tests it on the M34 anyway.

1. Main menu: **Request assistant role (S2b)**, or **Open default assistant settings** and pick *Call Answer Spike*
   under Digital assistant app. The menu status shows `OK assistant role (S2b)` when held.
2. Only one app can hold the call screening role. If the Call Answer app holds it, open the spike and tap
   **Request call screening role**, then confirm the switch. After the test, open the Call Answer app and request
   the role again (or Settings > Apps > Default apps > Caller ID & spam).
3. Set the S1 mode to `AUTO_ANSWER_ONLY`. Open **S2b**. Run both buttons in separate calls: **VIS** (capture inside
   the VoiceInteractionService) and **S2CaptureService** (comparison). Leave "Start when OFFHOOK" ticked.
4. Call the M34 from the second phone, answer on **loudspeaker**, and speak for the whole capture (4 sources x 10 s).
5. Tap **Export log** (main menu). Lines tagged `VIS/<source>` or `FGS/<source>` give `isClientSilenced`,
   `rmsFirst2s`, `perSecondRms`, audio mode and speakerphone. One WAV per source is in the `s2` folder.
6. Pass: any source with rms clearly above 0 and `isClientSilenced=false` during the call.
7. Restore afterwards: Settings > Apps > Default apps > Digital assistant app, choose Google (or Gemini).

## S3: Agent speech audible to the Caller, and speaker route
1. Set the S1 mode to `AUTO_ANSWER_ONLY` (or `OFF`). Open **S3**; the banner should be green.
2. Call the M34 from the second phone and answer it (on loudspeaker) so a call is active.
3. Try the speaker routes and note which one turns the speaker on:
   - Route 1 `setSpeakerphoneOn`, route 2 `setCommunicationDevice`: tap, then check the in-call screen.
4. Pick a language and sentence number, then a usage (`VOICE_COMMUNICATION`, `ASSISTANT`,
   `MEDIA`) and a call volume (60 to 100%).
5. Tap **Play** and listen on the second phone. Tick **Caller heard it** and tap the log
   button to record the result. You can leave the in-call screen and come back to the spike.
6. Repeat across the three usages and a few volumes. Tap **Stop** while a sentence is
   playing to log the stop latency.
7. Pass: some usage and volume setting is clearly audible to the Caller, and at least one
   speaker route works.
## Export and report

- Tap **Export log** on the spike menu and send the file to yourself.
- Give the log plus notes on which route, usage and source worked.
- Results go in a "Spike results" subsection of design.md (under "Feasibility spike"),
  and they decide the build flags (`ASR_ENGINE`, `SILENT_SMS_ENABLED`).
- Go/no-go (task 2): S1, S2 and S3 must pass.
