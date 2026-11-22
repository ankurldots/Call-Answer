# Spike runbook: S0 to S3 on the Galaxy M34 5G

Steps are based on what the spike screens do. They have not been run on the phone yet,
so button labels may differ slightly.

## Setup (once)

1. Install the spike build: `./gradlew :spike:installDebug` (phone is on wireless adb).
2. Open the Spike app, tap **Request runtime permissions**, allow everything.
3. Tap **Request call screening role** and accept.
4. Tap **Battery: set to Unrestricted** and set the app to Unrestricted.
5. Tap **Open accessibility settings** and turn on the spike's accessibility service.
   If it is greyed out: tap the **App info** button, open the three-dot menu, choose
   **Allow restricted settings**, then try again.
6. Tap **Full-screen intent access** and allow it.
7. Go back to the spike menu. Every line in the status list should read OK.
8. Have a second phone ready to call the M34, using a number that is not in the M34's contacts.

Tap **Clear log** before you start so the exported log only holds this run.

## S0: device readout (1 minute)

- Open **S0**. It logs model, Android and One UI versions, RAM, carrier and similar.

## S1: screen, answer, foreground service, end call

1. Open **S1**. Tap **Request phone and notification permissions** if it asks.
2. Do some runs with the screen off or locked and some with it unlocked.
3. Call the M34 from the second phone.
4. The app should screen the call, answer it and show the foreground-service notification.
   It ends the call by itself after about 15 s.
5. Repeat 5 times, including at least one run with the screen locked.
6. Check the log for: ring-to-answer time, foreground-service start result,
   notification post time, and the `endCall()` result.
7. Pass: every call answered within about 2 s and the service starts without a
   `ForegroundServiceStartNotAllowedException`.
8. If the service start is refused, the app posts a full-screen notification instead.
   Note whether that fallback worked.

## S2: microphone capture

1. Open **S2**. Leave the owner box unticked (the accessibility service captures).
2. Run once with no call, as a baseline.
3. Call the M34 from the second phone and answer it. Switch to **loudspeaker** on the M34.
4. Set a start delay of about 10 s, tap **Start**, and speak or play audio from the second
   phone. The app records each source in turn: `VOICE_RECOGNITION`, `MIC`, `UNPROCESSED`,
   `VOICE_COMMUNICATION`.
5. Repeat with the **plain foreground service** box ticked.
6. Check the log for the `isClientSilenced` values and the RMS and dBFS per source.
   WAVs are saved in the app's `s2` folder.
7. Pass: at least one source records the second phone's voice at a clear level
   (RMS well above the baseline) and `isClientSilenced` is false.
   Note which owner and which source worked.

## S3: Agent speech audible to the Caller, and speaker route

1. Open **S3**.
2. Call the M34 from the second phone and answer it so a call is active.
3. Tap the three route buttons in order and note which one turns the speaker on:
   `setSpeakerphoneOn`, then `setCommunicationDevice`, then the accessibility click on the
   Samsung Speaker button.
4. Pick a language and sentence number, then a usage (`VOICE_COMMUNICATION`, `ASSISTANT`,
   `MEDIA`) and a call volume (60 to 100%).
5. Tap **Play** and listen on the second phone. Tick **Caller heard it** and tap the log
   button to record the result.
6. Repeat across the three usages and a few volumes. Tap **Stop** while a sentence is
   playing to log the stop latency.
7. Pass: some usage and volume setting is clearly audible to the Caller, and at least one
   speaker route works.

## Export and report

- Tap **Export log** on the spike menu and send the file to yourself.
- Give the log plus notes on which route, usage, owner and source worked.
- Results go in a "Spike results" subsection of design.md (under "Feasibility spike"),
  and they decide the build flags (`PATH_A_SUPPORTED`, `ASR_ENGINE`, `SILENT_SMS_ENABLED`).
- Go/no-go (task 2): S1, S2 and S3 must pass.
