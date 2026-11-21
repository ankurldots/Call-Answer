# Implementation Plan: AI Call Screening Agent

## Overview

The plan is in Kotlin, the language used by the design. It runs in four phases:

1. A throwaway `:spike` app proves the platform decisions D1 to D6 on the User's Samsung Galaxy M34 5G (spike S0 to S8). A go/no-go checkpoint follows. Nothing else is built until it passes.
2. The pure Kotlin/JVM `:core` module holds all decision logic: models, validators, classifier, gate, delivery, stalling, network routing, echo filter, voice and speaker policy, recording matcher, spam reporting and the conversation engine reducer. Each piece is followed by its property-based tests (Kotest, at least 100 iterations, tagged `// Feature: ai-call-screening-agent, Property N: ...`).
3. The Android `:app` module wires `:core` to Room, DataStore, Telecom, audio, ASR/VAD/TTS, notifications, SAF/Media3 and SMS, and builds the Compose screens from the shared components in `ui/components` and the single theme in `ui/theme`.
4. Final on-device validation on the M34 5G: patchy-network matrix, airplane-mode tests and end-to-end call scenarios in Hindi, English and Hinglish.

Build flags decided by the spike: `PATH_A_SUPPORTED` (S6 Path A, feeds `pathAEnabled`), `ASR_ENGINE` (S7: `WHISPER_BASE_INT8` or `VOSK_STREAMING`), `SILENT_SMS_ENABLED` (S8).

## Tasks

- [ ] 1. Build the feasibility spike app (`:spike`) for the Galaxy M34 5G
  - [ ] 1.1 Create the Gradle root project and the `:spike` Android module
    - Root `settings.gradle.kts`, `gradle/libs.versions.toml`, Kotlin and Android Gradle plugin setup, `minSdk 29`, `targetSdk` of the current stable API
    - `:spike` manifest declaring every component and permission the spike needs up front (CallScreeningService, AccessibilityService with package filter `com.samsung.android.incallui` and `com.samsung.android.dialer`, foreground service type `mediaPlayback`, `READ_CONTACTS`, `ANSWER_PHONE_CALLS`, `READ_PHONE_STATE`, `CALL_PHONE`, `RECORD_AUDIO`, `POST_NOTIFICATIONS`, `SEND_SMS`, `USE_FULL_SCREEN_INTENT`, `<queries>` for `SENDTO sms:`)
    - A spike menu screen with one entry per check S0 to S8, a shared `SpikeLog` that writes timestamped results to an exportable text file, and role/permission request buttons
    - S0 readout: model number, Android and One UI version, security patch level, RAM, carrier, VoLTE state, default dialer package, and whether the Samsung call-recording settings activity resolves
    - _Requirements: 9.2, 12 (platform constraint), target-device constraint_

  - [ ] 1.2 Implement spike S1: screen, answer, foreground service and end call
    - `SpikeScreeningService.onScreenCall` logs number and presentation, responds allow, starts a foreground service and calls `TelecomManager.acceptRingingCall()` with 100 ms retries up to 2 s
    - Log ring-to-answer time, foreground-service start result (including `ForegroundServiceStartNotAllowedException`), notification post time, and `endCall()` result; fallback start through a full-screen-intent notification
    - _Requirements: 1.1, 1.2, 1.4, 13.12, 13.19_

  - [ ] 1.3 Implement spike S2: microphone capture as accessibility service
    - `SpikeAccessibilityService` owns an `AudioRecord` (16 kHz mono PCM16); a toggle captures from a plain foreground service instead for comparison
    - Cycle sources `VOICE_RECOGNITION`, `MIC`, `UNPROCESSED`, `VOICE_COMMUNICATION`; log `AudioRecordingCallback.isClientSilenced()`; dump one WAV per source to app storage with an RMS summary in the log
    - _Requirements: 13.15, platform constraint on call audio_

  - [ ] 1.4 Implement spike S3: Agent speech audible to the Caller, and speaker route
    - `AudioTrack` player for bundled test WAVs of 10 Hindi and 10 English sentences, switchable usage (`USAGE_VOICE_COMMUNICATION`, `USAGE_ASSISTANT`, `USAGE_MEDIA`) and call volume 60 to 100%
    - Measure stop latency (`pause` plus `flush`) and log it
    - Speaker route attempts in order: `setSpeakerphoneOn(true)`, `setCommunicationDevice(speaker)`, accessibility click on the Samsung Speaker button; log which one works
    - _Requirements: 13.1, 13.6, 13.10, 13.17_

  - [ ] 1.5 Implement spike S4: echo handling prototype
    - Prototype `EchoGate` windows `[ttsStart - 50 ms, ttsEnd + 600 ms]` and a token-similarity text filter (threshold 0.7, 15 s look-back) over the S2 capture and the S3 player timestamps
    - Optional toggle for reference AEC (WebRTC AEC3 or speexdsp) with the TTS PCM as reference; log a 20-turn result table (Agent text found in Caller transcript yes/no)
    - _Requirements: 13.7, 13.15_

  - [ ] 1.6 Implement spike S5: Samsung recording folder probe
    - SAF folder picker (`ACTION_OPEN_DOCUMENT_TREE`, persisted permission); after each call, list files with name, MIME type, size, last-modified time and delay after call end
    - Log the folder path, the file name pattern and which sides (Caller, Agent, User) are audible, entered by the User in a note field
    - _Requirements: 9.11, 12.1, 12.2, 12.3_

  - [ ] 1.7 Implement spike S6: transfer Path A and Path B
    - Path A: `TelecomManager.placeCall(tel:target)` during the active call, accessibility click on Samsung Merge, 30 s no-answer timer, end second call and click Swap/Resume; run with mobile data off
    - Path B: Takeover_Alert on channel `takeover_alert` (`IMPORTANCE_HIGH`, category `CALL`, full-screen intent, sound, vibration), an App-played alert tone through the S3 player, and a "Take over" action that reaches the foreground service from the lock screen; test with and without full-screen intent access
    - Log 5 tries per path with pass/fail
    - _Requirements: 4.2, 4.3, 4.7, 4.8, 8.10_

  - [ ] 1.8 Implement spike S7: on-device AI benchmark
    - Benchmark harness over 30 recorded loudspeaker utterances (Hindi, English, Hinglish) from the S2 dumps: Whisper base int8 (2 threads, big-core affinity where allowed) via sherpa-onnx or whisper.cpp, and Vosk small `hi` and `en-in` streaming
    - Measure WER, intent accuracy (with a draft phrase table), Silero VAD endpoint, Piper first-audio latency (sherpa-onnx), end-to-end latency p50/p90 cold and after a 10-minute sustained loop, and peak PSS; write a result table to the log
    - _Requirements: 2.1, 7.8, 7.15, 8.6, 8.11_

  - [ ] 1.9 Implement spike S8: 1909 hand-off and send
    - Button for `ACTION_SENDTO sms:1909` with `sms_body` "Unsolicited sales call, <number>, <DD/MM/YY>"
    - Button for `SmsManager.sendTextMessage("1909", ...)` (and `sendMultipartTextMessage` for a long body) with sent `PendingIntent` result codes logged; run with airplane mode on and with no signal; log the default SMS subscription on dual SIM
    - _Requirements: 14.4, 14.14, 14.16, 14.18, 14.24_

- [ ] 2. Checkpoint - Spike go/no-go on the Galaxy M34 5G
  - Ask the User to install the `:spike` build on the M34 5G, run S0 to S8 with a second phone as the Caller, and record every result in a new "Spike results" subsection of design.md (under "Feasibility spike").
  - Go/no-go rules: S1, S2 and S3 must pass. S6 Path B must pass. S6 Path A only sets the `PATH_A_SUPPORTED` build flag. S7 picks the ASR engine (`WHISPER_BASE_INT8` only if end-to-end p90 is at most 2.5 s cold and sustained with intent accuracy of at least 90%, otherwise `VOSK_STREAMING`). S8 decides `SILENT_SMS_ENABLED`.
  - If S2 or S3 fails, stop and ask the User to choose fallback F1 (speak-only assisted mode) or pivot F2 (VoIP/SIP number). Do not continue with task 3 until the User decides.
  - Ensure all tests pass, ask the user if questions arise.

- [ ] 3. Set up the production project structure
  - [ ] 3.1 Create the `:core` and `:app` Gradle modules
    - `:core`: Kotlin/JVM, kotlinx.serialization, kotlinx-coroutines (core and test), Kotest (`kotest-runner-junit5`, `kotest-property`), no Android dependencies; package skeleton from the design (`model`, `phone`, `gate`, `intent`, `language`, `delivery`, `conversation`, `stalling`, `echo`, `network`, `recording`, `settings`, `voice`, `report`)
    - `:app`: Android, Jetpack Compose with Material 3, Room, DataStore, WorkManager, Media3 ExoPlayer, sherpa-onnx, and Vosk or Whisper per the recorded S7 result; `noCompress "onnx", "bin"`; `android:allowBackup="false"` with `dataExtractionRules`; HTTPS-only network security config; package skeleton from the design
    - `BuildConfig` fields `PATH_A_SUPPORTED`, `ASR_ENGINE`, `SILENT_SMS_ENABLED` set from the spike results in design.md
    - One Kotest smoke test in `:core` and one JVM unit test in `:app` so `./gradlew test` runs green
    - _Requirements: 2.9, 8.11, 10.6, 14.28_

  - [ ] 3.2 Create the shared UI theme and components
    - `ui/theme`: one Material 3 `AppTheme` with color scheme (light and dark), typography and shapes; no hardcoded colors or sizes in screens
    - `ui/components`: `AppButton`, `AppCard`, `AppTextField` (with validation message slot), `AppSwitchRow`, `AppDialog`, `ChipRow`, `TranscriptBubble` (speaker label CALLER, AGENT, USER, LIVE), `StatusBanner`; each with content descriptions and semantics for accessibility, and a `@Preview`
    - _Requirements: 9.3, 9.7, 13.4_

  - [ ]* 3.3 Write Compose UI tests for the shared components
    - Validation message display in `AppTextField`, toggle semantics in `AppSwitchRow`, dialog confirm and dismiss in `AppDialog`
    - _Requirements: 9.3, 9.4_

- [ ] 4. Implement core models, defaults and settings validation
  - [ ] 4.1 Create the core data models and defaults
    - Enums `Language`, `VoiceGender`, `IntentCategory`, `NetworkQuality`, `Speaker`, `Outcome`, `EndReason`, `RecordingStatus`, `ReportStatus`, `TransferPath`, `AlertReason`, `TransferOutcome`, `LogNoteKind`, `NotificationKind`
    - `@Serializable` `LocalTimeHm`, `Gate`, `NightWindow`, `GateConfig`, `DeliveryStep`, `QuickReply`, `Settings`, `FrozenSettings` (including `pathAEnabled`), `Utterance`, `CallLogEntry`, `GateStatement`, `TakeoverRecord`, `InterruptionRecord`, `VoiceSubstitution`, `TransferRecord`, `RecordingInfo`, `ReportStamp`, `SpamReportInfo`
    - `DefaultGates` (Gate 1 Press Enclave Road near Punjab National Bank, Gate 3 opposite Aurobindo College, 22:30 to 05:30), `DefaultDeliverySteps` (the four English and Hindi steps with `{map_location}`), `DefaultQuickReplies` ("Please wait", "I am coming")
    - Shared `CoreJson` configuration (`encodeDefaults = true`, `ignoreUnknownKeys = true`, enums by name)
    - _Requirements: 1.7, 3.2, 9.1, 9.8, 9.14, 9.15, 9.25, 9.26, 10.1, 10.13, 11.16, 13.13_

  - [ ] 4.2 Implement `SettingsValidator` field checks
    - `validateTransferTarget` (`^\+?\d{7,15}$`), `validateMaxStalling` (whole seconds 30 to 600, non-numeric rejected), `validateNightWindow` (24-hour `HH:MM`, start != end), `validateGate` (each field 1 to 100 characters after trim), each returning a typed error that names the allowed format or range
    - _Requirements: 9.4, 9.6, 9.9, 9.10_

  - [ ]* 4.3 Write property test for settings field validation
    - **Property 22: Settings field validation**
    - **Validates: Requirements 9.4, 9.6, 9.9, 9.10**

  - [ ]* 4.4 Write unit tests for defaults
    - Default gates, Night_Window, Delivery_Steps, Quick_Replies, screening on, disclosure off, voice female, speaker on, silent send off, max stalling 120 s, Silence_Timeout 8 s
    - _Requirements: 1.7, 3.2, 9.1, 9.8, 9.14, 9.15, 9.25, 9.26, 11.16, 13.13_

- [ ] 5. Implement phone number matching and the screening decision
  - [ ] 5.1 Implement `PhoneNumberMatcher` and `ScreeningDecision`
    - `matchKey(raw)`: strip non-digits, keep the last 10 digits when longer than 10
    - `ScreeningDecision.decide(screeningEnabled, contactsReadable, numberHidden, knownContact)` with the order: disabled, contacts unreadable, hidden, known, otherwise answer
    - _Requirements: 1.2, 1.3, 1.5, 1.6, 1.8, 1.9_

  - [ ]* 5.2 Write property test for the screening decision
    - **Property 1: Screening decision**
    - **Validates: Requirements 1.2, 1.3, 1.5, 1.6, 1.9**

  - [ ]* 5.3 Write property test for phone number normalization
    - **Property 2: Phone number normalization equivalence**
    - **Validates: Requirements 1.8**

- [ ] 6. Implement text normalization, intent classification and language detection
  - [ ] 6.1 Implement `TextNormalizer`, `PhraseTables` and `RuleIntentClassifier`
    - `TextNormalizer`: NFKC, lower case, Devanagari nukta normalization, punctuation strip, space collapse
    - `PhraseTables`: word-boundary patterns in Devanagari, Roman Hinglish (spelling variants) and English for DELIVERY, DOOR_NOT_OPENED and SOLICITATION (split by `SolicitationTopic` LOAN, PROPERTY, OTHER_SALES), a separate Gate_Query table, plus repeat-request, acknowledgement and timing-question tables used by the engine; tuned with the S7 ASR output
    - `classify`: earliest match start wins, ties to the longer match then DELIVERY, DOOR_NOT_OPENED, SOLICITATION; no match gives UNKNOWN; `gateQuery` from the Gate_Query table only
    - _Requirements: 2.2, 2.3, 2.4, 2.5, 2.6, 2.7, 2.9, 2.11, 2.12, 7.7, 11.13, 11.15_

  - [ ]* 6.2 Write property test for category recognition in all scripts
    - **Property 3: Classifier recognizes every category in all scripts**
    - **Validates: Requirements 2.2, 2.3, 2.4, 2.5, 2.6, 2.10, 7.7**

  - [ ]* 6.3 Write property test for earliest-match precedence
    - **Property 4: Earliest matching phrase wins**
    - **Validates: Requirements 2.7**

  - [ ]* 6.4 Write property test for Gate_Query flagging
    - **Property 5: Gate_Query is flagged and does not change the category**
    - **Validates: Requirements 2.11, 2.12, 11.13**

  - [ ] 6.5 Implement `ScriptLanguageDetector` and `ResponseLanguageResolver`
    - Bundled Hinglish lexicon resource; token rules (Devanagari or lexicon token is Hindi, other Roman letter token is English, digits and punctuation not counted); majority over 50%, first counted token on a tie, `null` when nothing is counted
    - `resolve(detected, lastIdentified) = detected ?: lastIdentified ?: EN`
    - _Requirements: 3.7, 7.1, 7.2, 7.3, 7.4, 7.5, 7.6_

  - [ ]* 6.6 Write property test for the mixed-language majority rule
    - **Property 11: Mixed-language majority rule**
    - **Validates: Requirements 7.5**

- [ ] 7. Implement colony gate selection and rendering
  - [ ] 7.1 Implement `GateSelector` and `GateInfoRenderer`
    - `isInNightWindow` for wrapping and non-wrapping windows (start inclusive, end exclusive), `selectGate(now: LocalTimeHm?, cfg)` returning `Single` or `Both` (time unreadable)
    - `GateInfoRenderer.render(selection, language, includeWindowTimes)` with Hindi and English templates containing only gate number, location and landmark, plus Night_Window times when the Caller asked about timing or both gates are stated
    - _Requirements: 11.2, 11.3, 11.4, 11.5, 11.12, 11.14, 11.15, 11.16, 11.17, 11.18_

  - [ ]* 7.2 Write property test for gate selection
    - **Property 29: Gate selection partition**
    - **Validates: Requirements 11.2, 11.3, 11.4, 11.5, 11.17, 11.18**

  - [ ]* 7.3 Write property test for gate statement content
    - **Property 30: Colony_Gate_Info content**
    - **Validates: Requirements 11.12, 11.14**

- [ ] 8. Implement Delivery_Directions rendering and step-list validation
  - [ ] 8.1 Implement `DeliveryDirectionsRenderer` and `StepListValidator`
    - `render(steps, language, mapLocation, gateInfoText, translator)` returns `[gateInfoText, step1, ...]`; blank target-language text uses the translated other-language text or the text as is; `{map_location}` replaced, or removed with whitespace collapsed when no Map_Location is set
    - `StepListValidator` (1 to 10 steps, each with at least one non-blank text, no text over 200 characters, missing-language warnings) and `SettingsValidator.validateSteps` delegating to it
    - _Requirements: 3.2, 3.5, 3.10, 9.16, 9.18, 9.19, 9.20, 9.23, 9.24_

  - [ ]* 8.2 Write property test for Delivery_Directions rendering
    - **Property 8: Delivery_Directions rendering**
    - **Validates: Requirements 3.2, 3.5, 3.10, 9.19, 9.23, 9.24, 11.6, 11.7**

  - [ ]* 8.3 Write property test for step-list validation
    - **Property 23: Delivery_Step list validation**
    - **Validates: Requirements 9.16, 9.18, 9.20**

- [ ] 9. Implement the stalling library, PII guard and fictitious gate
  - [ ] 9.1 Implement `PiiGuard`
    - Protected values from `protectedTerms`, `mapLocation`, `transferTarget`, the phone's own number and the gate values; normalized, digit-insensitive, token-level matching
    - Pattern rejection: digit runs of 6 or more, email, PAN, Aadhaar, IFSC and card-like patterns; `containsProtected`, `sanitizePrompt`, `acceptReply` (no protected value, exactly one question, expected language)
    - _Requirements: 5.4, 5.7, 10.7, 14.8_

  - [ ] 9.2 Implement `FictitiousReplyLibrary`, `FictitiousGate` and `PromptBuilder`
    - Templates per topic (loan, property, insurance or credit card, generic) in Hindi and English, each ending with exactly one follow-up question; slot pools filtered against protected values at construction; seeded selection; fixed `StallFiller` phrases
    - `FictitiousGate.answer(cfg, language, seed)` from pools excluding the configured gate numbers, locations, landmarks and Night_Window times
    - `PromptBuilder` taking only the sanitized latest Caller utterance, the language and recent turns (no `Settings` or `Gate` parameters), with the fixed system prompt
    - _Requirements: 5.2, 5.4, 5.5, 5.7, 5.10, 10.7, 11.11_

  - [ ]* 9.3 Write property test for stalling reply shape
    - **Property 12: Stalling reply shape**
    - **Validates: Requirements 5.2, 5.5**

  - [ ]* 9.4 Write property test for the PII guard
    - **Property 13: PII guard on replies and prompts**
    - **Validates: Requirements 5.4, 5.7, 10.7**

  - [ ]* 9.5 Write property test for fictitious gate information
    - **Property 14: Fictitious gate information differs from real configuration**
    - **Validates: Requirements 11.11**

- [ ] 10. Implement the network classifier and provider router
  - [ ] 10.1 Implement `NetworkClassifier`, `ProviderRouter` and the cloud deadline
    - `classify(samples, nowMs, windowMs = 2000)` per the UNAVAILABLE, DEGRADED (worst RTT above 1500 ms or failed share above 10%), AVAILABLE rules
    - `ProviderRouter.cloudOrLocal(deadlineMs, cloud, local)` reading quality once per request, local first, `withTimeoutOrNull` for the cloud, `Routed` with source LOCAL, CLOUD, LOCAL_AFTER_TIMEOUT or LOCAL_AFTER_ERROR
    - `cloudDeadlineMs(elapsedSinceEndOfSpeech) = min(1500, 2400 - elapsed)`
    - _Requirements: 8.1, 8.2, 8.3, 8.4, 8.5_

  - [ ]* 10.2 Write property test for network classification
    - **Property 18: Network classification**
    - **Validates: Requirements 8.1**

  - [ ]* 10.3 Write property test for cloud-or-local routing (virtual time)
    - **Property 19: Cloud-or-local routing**
    - **Validates: Requirements 8.2, 8.4**

- [ ] 11. Implement the echo text filter
  - [ ] 11.1 Implement `EchoTextFilter`
    - Agent window model (`[ttsStart - 50 ms, ttsEnd + 600 ms]`), removal of ASR segments that overlap an Agent window and have normalized token similarity of at least 0.7 to an Agent utterance from the last 15 s
    - _Requirements: 13.7, 13.15_

  - [ ]* 11.2 Write property test for echo exclusion
    - **Property 37: Echo exclusion**
    - **Validates: Requirements 13.15**

- [ ] 12. Implement voice selection and speaker policy
  - [ ] 12.1 Implement `VoiceSelector` and `SpeakerPolicy`
    - `VoiceSelector.select(want, language, available)`: same gender and language with bundled first; substitution flag; any voice of the language when no same-gender voice exists
    - `SpeakerPolicy.decide(speakerSetting, headsetConnected)`: TurnOn, LeaveOff, LeaveOffHeadset
    - _Requirements: 7.10, 7.11, 7.12, 7.13, 13.1, 13.3, 13.16_

  - [ ]* 12.2 Write property test for voice selection
    - **Property 21: Voice selection**
    - **Validates: Requirements 7.10, 7.11, 7.12, 7.13**

  - [ ]* 12.3 Write property test for the speaker policy
    - **Property 33: Speaker policy**
    - **Validates: Requirements 13.1, 13.3, 13.16**

- [ ] 13. Implement the recording matcher and link policy
  - [ ] 13.1 Implement `RecordingMatcher` and `LinkPolicy`
    - `choose(candidates, alreadyLinked, callerNumber, callStartMs, callEndMs, windowMs = 60_000)` with audio-type filter, Link_Window rule, number preference, closest start time and tie-breakers; lenient file-name parsing for the S5 name pattern
    - `LinkPolicy`: search schedule 0, 20, 40, 60 s; status rules NOT_FOUND, NO_FOLDER_SELECTED, FOLDER_ACCESS_LOST; pure `applyLink(links, entryId, uri)` with idempotence and one-entry-per-file rule
    - _Requirements: 12.1, 12.2, 12.4, 12.5, 12.6, 12.7, 12.11, 12.15, 12.16_

  - [ ]* 13.2 Write property test for recording candidate selection
    - **Property 31: Recording candidate selection**
    - **Validates: Requirements 12.1, 12.2, 12.4**

  - [ ]* 13.3 Write property test for link idempotence and uniqueness
    - **Property 32: Recording link idempotence and uniqueness**
    - **Validates: Requirements 12.16**

  - [ ]* 13.4 Write unit tests for the retry schedule and statuses
    - Retry at 20 s intervals up to 3 times, NOT_FOUND after the last retry with one notification, NO_FOLDER_SELECTED without a folder
    - _Requirements: 12.5, 12.6, 12.7_

- [ ] 14. Implement the spam report builder and report status machine
  - [ ] 14.1 Implement `TraiReport`, `BriefDescriptionGenerator`, `SenderNumber` and `SpamReportBuilder`
    - `TraiReport.NUMBER = "1909"` (constant, not in Settings), `MAX_LENGTH = 160`, `SEPARATOR = ", "`
    - `BriefDescriptionGenerator.generate` returning only the four constants with LOAN over PROPERTY over sales precedence, "Unsolicited call" for non-SOLICITATION
    - `SenderNumber.normalize` (strip formatting, keep a leading `+`, accept `^\+?\d{7,15}$`, null for unknown)
    - `SpamReportBuilder.validateDescription` (trim, non-empty, `0x20..0x7E`, no comma, no protected value via `PiiGuard`), `reportDate` (`dd/MM/yy` in the injected zone), `build` (truncate the description only, trailing spaces removed), `parse`
    - _Requirements: 14.4, 14.5, 14.6, 14.7, 14.8, 14.9, 14.10, 14.11, 14.12, 14.13, 14.29, 14.30_

  - [ ]* 14.2 Write property test for the spam report message round trip
    - **Property 38: Spam report message round trip**
    - **Validates: Requirements 14.5, 14.10, 14.29**

  - [ ]* 14.3 Write property test for Brief_Description precedence
    - **Property 39: Brief_Description generation follows the topic precedence**
    - **Validates: Requirements 14.6, 14.7**

  - [ ]* 14.4 Write property test for safe Brief_Descriptions
    - **Property 40: Accepted Brief_Descriptions never contain a comma, non-ASCII text or a protected value**
    - **Validates: Requirements 14.8, 14.9**

  - [ ]* 14.5 Write property test for Sender_Number normalization
    - **Property 41: Sender_Number normalization**
    - **Validates: Requirements 14.11, 14.13**

  - [ ]* 14.6 Write property test for the Report_Date
    - **Property 42: Report_Date is the call-start date in the device zone**
    - **Validates: Requirements 14.12**

  - [ ] 14.7 Implement `ReportStatusMachine` and `ReportFlowPolicy`
    - `ReportEvent`, `ReportAction`, `ReportStep` and `reduce` following the transition table: cannot-report, repeat confirmation, silent path, confirmation path, confirm, cancel with `pendingRestore`, hand-off, no messaging app, no signal, send result, send timeout, stale attempt ignored; time and zone stamped on every status change
    - `ReportFlowPolicy` computing `senderAvailable`, `silentSend` (setting and `SILENT_SMS_ENABLED` flag) and `sendSmsGranted`
    - _Requirements: 14.3, 14.13, 14.14, 14.15, 14.16, 14.17, 14.18, 14.19, 14.20, 14.21, 14.24, 14.25, 14.26, 14.27_

  - [ ]* 14.8 Write property test for at most one dispatch
    - **Property 43: Repeated "Report spam" dispatches at most one report**
    - **Validates: Requirements 14.3, 14.26, 14.27**

  - [ ]* 14.9 Write property test for the silent-send condition
    - **Property 44: Silent sending only when the setting is on and SEND_SMS is granted**
    - **Validates: Requirements 14.15, 14.16, 14.17**

  - [ ]* 14.10 Write property test for Report_Status invariants
    - **Property 45: Report_Status invariants**
    - **Validates: Requirements 14.21, 14.25**

  - [ ]* 14.11 Write property test for cancel restore
    - **Property 46: Cancel restores the previous Report_Status**
    - **Validates: Requirements 14.20**

  - [ ]* 14.12 Write unit tests for spam report examples
    - The three generated descriptions with English, Devanagari and Hinglish transcripts; "Unsolicited call" for DELIVERY; messages at exactly 160 and 161 characters; `+91` and 10-digit senders; 31/12 and 01/01 dates across zones
    - _Requirements: 14.6, 14.7, 14.10, 14.11, 14.12_

- [ ] 15. Implement the conversation engine core (greeting, dispatch, language)
  - [ ] 15.1 Create the engine types and the `ResponseRenderer`
    - `Phase`, `Mode`, `ConversationState`, `TransferState`, `CallSession`, `ConversationEvent`, `Effect`, `SpeechSpec`, `EngineContext`, `Transition`
    - `ResponseRenderer` turning each `SpeechSpec` into Hindi or English text from templates (Greeting "Hello", Disclosure, AskReason, AskRepeatRequest, TransferNotice, PleaseWait, NobodyAvailable, WillReturnCall, NoFurtherRepetitions, SilencePrompt, BridgingPhrase, PoliteGoodbye, FallbackPhrase), using `GateInfoRenderer`, `DeliveryDirectionsRenderer` and `FictitiousGate`; every rendered text gets a unique `responseId`
    - _Requirements: 1.4, 3.10, 4.1, 6.1, 10.11, 11.11, 11.14, 13.11_

  - [ ] 15.2 Implement the base reducer
    - `CallAnswered` speaks Greeting, then Disclosure when the frozen Disclosure_Setting is on (at most once, within the first 5 s)
    - `CallerUtterance`: classify, resolve language, dispatch per the table (DELIVERY, DOOR_NOT_OPENED with or without gate query, SOLICITATION, UNKNOWN with or without gate query); reclassification where UNKNOWN keeps the current category; empty transcript asks once to repeat; utterances appended to the transcript
    - `spokenResponseIds` so a completed response is never spoken twice
    - _Requirements: 1.4, 2.8, 2.10, 3.1, 4.1, 5.1, 6.1, 7.2, 7.3, 7.4, 7.6, 8.5, 10.11, 11.1, 11.8, 11.9, 11.10_

  - [ ]* 15.3 Write property test for reclassification
    - **Property 6: Reclassification follows the latest determined utterance**
    - **Validates: Requirements 2.8**

  - [ ]* 15.4 Write property test for response dispatch
    - **Property 7: Response dispatch by category and Gate_Query**
    - **Validates: Requirements 3.1, 4.1, 5.1, 6.1, 11.1, 11.8, 11.9, 11.10**

  - [ ]* 15.5 Write property test for response language resolution
    - **Property 10: Response language resolution**
    - **Validates: Requirements 3.7, 7.2, 7.3, 7.4, 7.6**

  - [ ]* 15.6 Write property test for no repeated response
    - **Property 20: No completed response is repeated**
    - **Validates: Requirements 8.5**

  - [ ]* 15.7 Write property test for the disclosure rule
    - **Property 28: Disclosure only when enabled**
    - **Validates: Requirements 10.11**

- [ ] 16. Implement the delivery flow in the engine
  - [ ] 16.1 Add delivery handling to the reducer
    - Directions with gate info first; clock read (event `localTime`) at the start of each gate statement and each repetition; repeat requests up to 3, then NoFurtherRepetitions and EndCall; Gate_Query answers not counted
    - End on acknowledgement, hang-up or 10 s silence after the directions; `SendMapSms` when Map_Location is set and the number is known, `Notify(MAP_LOCATION_MISSING)` otherwise
    - Unreadable time: both gates and times, `LogNote(TIME_READ_FAILED)`; every gate statement recorded as `GateStatement` (proactive or query, real or fictitious)
    - _Requirements: 3.3, 3.4, 3.5, 3.6, 3.9, 3.10, 3.11, 10.9, 11.5, 11.6, 11.7, 11.17_

  - [ ]* 16.2 Write property test for the repetition limit
    - **Property 9: Repetition limit**
    - **Validates: Requirements 3.3, 3.9, 3.11**

  - [ ]* 16.3 Write unit tests for delivery end triggers and map SMS effects
    - Acknowledgement, hang-up, 10 s silence; SendMapSms with and without Map_Location; hidden number gives no SMS
    - _Requirements: 3.4, 3.5, 3.6, 3.8_

- [ ] 17. Implement stalling, silence, clarification, interruption and failure handling in the engine
  - [ ] 17.1 Add the remaining phase rules to the reducer
    - Stalling: `Speak(Stall)` per utterance with fictitious gate information on a gate query, max stalling duration via `Tick` with polite end and MAX_DURATION, stalling start recorded for the duration
    - Silence: greeting repeated once then end with silent-caller notification; 3 consecutive stalling silence prompts then end with CALLER_SILENCE; UNKNOWN ends with WillReturnCall after 2 clarification prompts and notifies with number (or "number unavailable") and transcript
    - Audio: `CallerAudioUnusable` asks to repeat at most 3 consecutive times; more than 10 s without usable audio ends the call with an `InterruptionRecord`
    - Failures: `ResponseFailed` speaks FallbackPhrase (in stalling: StallFiller, one retry, then polite end and log); `SpeechSynthesisFailed` retries once then FallbackPhrase in the last working language, call stays open
    - _Requirements: 5.2, 5.3, 5.6, 5.8, 5.9, 5.10, 6.2, 6.3, 6.4, 6.5, 6.6, 6.7, 7.9, 8.7, 8.8, 8.9, 11.10, 11.11_

  - [ ]* 17.2 Write property test for silence and clarification limits
    - **Property 15: Silence and clarification limits**
    - **Validates: Requirements 5.6, 6.2, 6.4, 6.5, 6.7, 11.10**

  - [ ]* 17.3 Write property test for audio interruption limits
    - **Property 16: Audio interruption limits**
    - **Validates: Requirements 8.7, 8.8**

  - [ ]* 17.4 Write property test for the maximum stalling duration
    - **Property 17: Maximum stalling duration**
    - **Validates: Requirements 5.3, 5.8**

  - [ ]* 17.5 Write unit tests for failure retries and unknown-call notifications
    - Stalling filler then retry then end; synthesis retry then fallback; processing error fallback; UNKNOWN notification payload with and without number
    - _Requirements: 5.10, 6.3, 6.6, 7.9, 8.9_

- [ ] 18. Implement the transfer flow in the engine (Path A and AWAITING_TAKEOVER)
  - [ ] 18.1 Add transfer handling to the reducer
    - On `AgentSpeechFinished` of TransferNotice: with `pathAEnabled` and a Transfer_Target, `StartTransfer` plus `Notify(TRANSFER_STARTED)` and phase TRANSFER; otherwise `RaiseTakeoverAlert` (PATH_A_UNAVAILABLE or TARGET_NOT_CONFIGURED), `Speak(PleaseWait)`, `Notify(TRANSFER_STARTED)`, `Notify(TRANSFER_TARGET_MISSING)` without a target, phase AWAITING_TAKEOVER
    - `TransferResult`: MERGED gives TRANSFERRED; NO_ANSWER_30S or FAILED gives `RaiseTakeoverAlert`, PleaseWait and AWAITING_TAKEOVER; FAILED adds `Notify(TRANSFER_FAILED)` and `LogNote(TRANSFER_FAILED)`
    - AWAITING_TAKEOVER: PleaseWait every 10 s from `lastPleaseWaitAtMs`, no other speech, Caller utterances transcribed only; at 30 s after the alert one NobodyAvailable, outcome NO_TAKEOVER_30S, phase LISTENING, no `ClearTakeoverAlert`; CALL_ENDED_WHILE_WAITING on call end; `TransferRecord` kept in state
    - _Requirements: 4.1, 4.2, 4.3, 4.4, 4.5, 4.6, 4.7, 4.9, 4.10, 4.11, 8.10_

  - [ ]* 18.2 Write property test for the first transfer effect
    - **Property 48: First transfer effect follows the path rule**
    - **Validates: Requirements 4.2, 4.5, 4.10**

  - [ ]* 18.3 Write property test for "please wait" spacing
    - **Property 49: Only "please wait", at most 15 s apart, while awaiting take over**
    - **Validates: Requirements 4.7, 4.11**

  - [ ]* 18.4 Write property test for "nobody available"
    - **Property 51: Exactly one "nobody available" after 30 s without take over**
    - **Validates: Requirements 4.3, 4.9**

  - [ ]* 18.5 Write unit tests for transfer outcomes
    - TRANSFER_STARTED and TRANSFER_FAILED notifications with the Caller's number, `FAILED_THEN_ALERT` with the 8.10 log note, no "please wait" during the Path A wait
    - _Requirements: 4.4, 4.6, 8.10_

- [ ] 19. Implement override modes in the engine (take over, mute, resume, quick replies, end call)
  - [ ] 19.1 Add override handling to the reducer
    - `UserTakeOver` (origin ignored): StopSpeaking, ClearTakeoverAlert when an alert is shown, mode TAKE_OVER, takeover start stored; in AWAITING_TAKEOVER or TRANSFER also `LogNote(TRANSFER_COMPLETED_BY_USER)` and outcome TAKEN_OVER_BY_USER; a later `TransferResult` only updates the record
    - TAKE_OVER: no Speak, EndCall, StartTransfer, RaiseTakeoverAlert, silence or max-duration reaction; utterances labelled LIVE and appended
    - `UserMute`: StopSpeaking, mode AGENT_MUTED, no Speak; timers and outcomes still run; `nobodyAvailablePending` when 30 s pass while muted
    - `UserResume`: Speak(BridgingPhrase) in the Caller's language, then NobodyAvailable when pending, mode AGENT_ACTIVE with phase and intent unchanged; takeover period closed as a `TakeoverRecord`
    - `QuickReplySent` speaks the reply in the Caller's language (ignored while muted or in TAKE_OVER); `UserEndCall` gives EndCall from every phase; `VoiceActivity` never stops speech
    - _Requirements: 4.8, 13.6, 13.7, 13.8, 13.9, 13.10, 13.11, 13.12, 13.14, 13.18, 13.20, 13.21_

  - [ ]* 19.2 Write property test for take over invariants
    - **Property 34: Take over invariants**
    - **Validates: Requirements 5.3, 13.6, 13.8, 13.9, 13.12**

  - [ ]* 19.3 Write property test for mute and voice activity
    - **Property 35: Mute invariants and no voice-activity interruption**
    - **Validates: Requirements 13.7, 13.10**

  - [ ]* 19.4 Write property test for resume
    - **Property 36: Resume restores the conversation**
    - **Validates: Requirements 13.11**

  - [ ]* 19.5 Write property test for take over equivalence
    - **Property 50: Take over from the Takeover_Alert equals take over from the Live_Call_Screen**
    - **Validates: Requirements 4.8, 13.20, 13.21**

  - [ ]* 19.6 Write unit tests for controls during a transfer
    - Mute and resume during the Path A wait and during AWAITING_TAKEOVER; the 30 s point passing while muted, then NobodyAvailable right after the Bridging_Phrase; quick replies during a transfer
    - _Requirements: 13.10, 13.14, 13.20_

- [ ] 20. Implement Call_Log entry building, ordering and serialization
  - [ ] 20.1 Implement `CallLogEntryBuilder` and `CallLogOrder`
    - Build a `CallLogEntry` from the final `ConversationState` and `CallSession`: number (null as unknown), start time and zone, whole-second duration 0 to 86,400, last language, final intent, outcome, end reason, transcript, gate statements, voice gender and substitutions, stalling duration, takeover records, interruption, notes, `TransferRecord`, recording status PENDING or NO_FOLDER_SELECTED, default `SpamReportInfo`
    - `CallLogOrder.sortForDisplay` (start time descending); `CallLogJson` encode and decode with `CoreJson`
    - _Requirements: 4.8, 5.9, 7.12, 7.13, 8.8, 10.1, 10.2, 10.4, 10.9, 10.12, 10.13, 12.7, 13.18_

  - [ ]* 20.2 Write property test for the Call_Log JSON round trip
    - **Property 25: Call_Log serialization round trip** (JSON encode and decode part)
    - **Validates: Requirements 10.1, 10.2, 10.8, 10.10**

  - [ ]* 20.3 Write property test for the Report_Status JSON round trip
    - **Property 47: Call_Log round trip includes the Report_Status** (JSON part)
    - **Validates: Requirements 10.8, 10.13**

  - [ ]* 20.4 Write property test for the entry built from the session
    - **Property 27: Call_Log entry reflects the session**
    - **Validates: Requirements 5.9, 10.9, 10.12, 13.18**

  - [ ]* 20.5 Write property test for Call_Log order
    - **Property 26: Call_Log order**
    - **Validates: Requirements 10.4**

- [ ] 21. Checkpoint - Core logic complete
  - Run `./gradlew :core:test`. Ensure all tests pass, ask the user if questions arise.

- [ ] 22. Implement the `:app` data layer
  - [ ] 22.1 Implement Room storage for the Call_Log and the Outbox
    - `CallLogEntity` with scalar columns (`startedAtEpochMs`, `zoneId`, `callerNumber`, `intent`, `language`, `durationSec`, `recordingStatus`, `recordingUri` with a unique index, `recordingDurationMs`, `recordingSizeBytes`), JSON columns (`transcript`, `gateStatements`, `takeovers`, `notes`, transfer, voice substitutions, interruption) and spam report columns (`reportStatus` NOT NULL default `'NOT_REPORTED'`, `reportedAtEpochMs`, `reportedZoneId`, `reportAttemptId`, `reportDispatched` default 0, `reportPendingRestore`)
    - `CallLogMapper` (`toEntity`, `toEntry`); `CallLogDao` with save (3 retries at 1 s, then "call not logged" notification hook), list ordered by start time descending, delete by id, `casReport(id, expectedStatus, expectedAttemptId, new)`, `linkRecording` in one transaction against the unique index, `updateRecordingStatus`; `OutboxDao`
    - _Requirements: 10.1, 10.2, 10.3, 10.4, 10.5, 10.6, 10.8, 10.10, 10.13, 12.3, 12.16, 14.25, 14.27_

  - [ ]* 22.2 Write property test for the Call_Log entity mapper round trip
    - **Property 25: Call_Log serialization round trip** (`toEntry(toEntity(entry))` part)
    - **Validates: Requirements 10.1, 10.2, 10.8, 10.10**

  - [ ]* 22.3 Write property test for the Report_Status entity mapper round trip
    - **Property 47: Call_Log round trip includes the Report_Status** (entity mapper part)
    - **Validates: Requirements 10.8, 10.13**

  - [ ] 22.4 Implement `SettingsStore` with DataStore
    - Typed DataStore with a JSON serializer for `Settings`; `update(candidate)` runs `SettingsValidator` and leaves the stored value unchanged on rejection; `snapshot()` builds `FrozenSettings` at call start, with `pathAEnabled = BuildConfig.PATH_A_SUPPORTED && transferTarget != null && accessibility service enabled`; cloud API key in an encrypted entry backed by the Android Keystore
    - _Requirements: 1.7, 4.2, 9.1, 9.4, 9.5, 9.6, 9.14, 9.15, 9.22, 9.25, 9.26, 13.13_

  - [ ]* 22.5 Write property test for settings persistence
    - **Property 24: Settings persistence round trip** (write, re-create the store, read)
    - **Validates: Requirements 1.7, 9.1, 9.5, 9.14, 9.15, 9.22, 9.25, 13.13**

  - [ ] 22.6 Implement `OutboxWorker`
    - WorkManager worker with `NetworkType.NOT_REQUIRED`, an extra run on `NetworkCallback.onAvailable`, delivery of deferred notifications and entries within 60 s of connectivity returning, items kept until delivered
    - _Requirements: 8.12_

  - [ ]* 22.7 Write instrumented tests for Room and the Outbox
    - Real SQLite round trip, unique `recordingUri` index, concurrent `casReport` with one winner, save retry then notification, Outbox flush after reconnect
    - _Requirements: 8.12, 10.3, 12.16, 14.27_

- [ ] 23. Implement the notification center and the call service shell
  - [ ] 23.1 Implement `NotificationCenter` and `LiveCallActivity` host
    - Channels `live_call`, `takeover_alert` (`IMPORTANCE_HIGH`, sound, vibration, category `CALL`, public lock-screen visibility), `post_call`, `alerts`
    - Builders for: contacts access required, Map_Location missing, map SMS failed, transfer started, transfer failed, Transfer_Target not configured (all with the Caller's number where required), speakerphone not turned on (headset or failure), unknown call with transcript, silent caller, call not logged, recording not found, folder access lost, permission banners
    - `LiveCallActivity` host (shows when locked, full-screen intent target); its Compose content is filled in task 29.4
    - _Requirements: 1.6, 3.5, 3.8, 4.4, 4.5, 4.6, 6.3, 6.6, 6.7, 10.3, 12.6, 12.11, 13.16, 13.17, 13.19_

  - [ ] 23.2 Implement the `CallForegroundService` shell
    - Foreground service type `mediaPlayback`, session lifecycle (`CallSession` with `FrozenSettings` snapshot), `StateFlow<LiveCallUiState>`, control intents for Take over, Mute Agent, Resume Agent, End call (each mapped to the engine event with the event time); fallback start through a full-screen-intent notification when a background start is refused, per the S1 result
    - _Requirements: 9.14, 9.15, 9.22, 9.25, 13.4, 13.19, 13.21_

  - [ ]* 23.3 Write Robolectric tests for notifications
    - Channel importance and category, full-screen intent present, action order Take over, Mute/Resume, End call, and number text in transfer notifications
    - _Requirements: 4.4, 4.5, 4.6, 13.19_

- [ ] 24. Implement call screening and answering
  - [ ] 24.1 Implement `ContactIndex`
    - In-memory `Set<String>` of `matchKey`s from `ContactsContract.CommonDataKinds.Phone`, loaded at App start and when the screening role is granted, refreshed by a `ContentObserver`; `readable` false on `SecurityException` or query error
    - _Requirements: 1.1, 1.6, 1.8_

  - [ ] 24.2 Implement `CallScreenerService` and `CallAnswerer`
    - `onScreenCall`: hidden-number check, `ScreeningDecision.decide`, respond allow, start `CallForegroundService`, call `CallAnswerer.answer()`; contacts unreadable leaves the call ringing and posts the notification within 5 s
    - `CallAnswerer`: `acceptRingingCall()` retried every 100 ms until RINGING or 2 s, OFFHOOK check, "could not answer" notification; `endCall()` with a notification fallback
    - _Requirements: 1.1, 1.2, 1.3, 1.5, 1.6, 1.9, 13.12_

  - [ ]* 24.3 Write Robolectric tests for screening
    - Known contact rings, unknown answers, hidden answers, screening off rings, contacts unreadable rings and notifies
    - _Requirements: 1.2, 1.3, 1.5, 1.6, 1.9_

- [ ] 25. Implement the audio path
  - [ ] 25.1 Implement `CallAudioAccessibilityService`, `CallAudioCapture` and `InCallUiAutomator`
    - Accessibility service config with minimum event types and the Samsung package filter; `AudioRecord` 16 kHz mono PCM16 with the source chosen in S2; `frames: Flow<PcmFrame>` and `silenced: StateFlow<Boolean>` from `AudioRecordingCallback`; silenced capture switches to assisted mode F1 and alerts the User
    - `InCallUiAutomator` for the Samsung Speaker, Merge and Swap/Resume buttons (view ids or labels recorded in S3 and S6)
    - _Requirements: 13.8, 13.15, 13.17, D1_

  - [ ] 25.2 Implement `AgentAudioPlayer` and `EchoGate`
    - `AudioTrack` with the usage chosen in S3, low-latency mode, chunks of at most about 8 s with 400 ms listening gaps, `stop()` within 500 ms (`pause` plus `flush`), playback start and end times from `getTimestamp()` published as Agent windows
    - `EchoGate` marking frames inside Agent windows so fully covered segments are not sent to ASR; alert tones also marked
    - _Requirements: 13.6, 13.7, 13.10, 13.15_

  - [ ] 25.3 Implement `AudioRouteController`
    - Headset detection through `AudioDeviceCallback`, `SpeakerPolicy.decide`, speaker on in the order `setSpeakerphoneOn`, `setCommunicationDevice`, `InCallUiAutomator` click; keep speaker on unless the User turns it off; raise `STREAM_VOICE_CALL` to 80% and restore at call end; notifications for headset and failure
    - _Requirements: 13.1, 13.2, 13.3, 13.16, 13.17_

  - [ ]* 25.4 Write tests for the audio path
    - Robolectric: route controller with headset connected and with all route methods failing; JVM: `EchoGate` window marking and chunk gap timing with a fake clock
    - _Requirements: 13.15, 13.16, 13.17_

- [ ] 26. Implement the transfer controller (Path A) and the Takeover_Alert (Path B)
  - [ ] 26.1 Implement `TransferController` behind the `pathAEnabled` flag
    - `connect(target)`: `placeCall`, wait up to 30 s for Merge to be enabled, press Merge, return MERGED; on timeout or failure end the second call, press Swap/Resume and return NO_ANSWER_30S or FAILED; uses the cellular voice network only
    - Never called when `FrozenSettings.pathAEnabled` is false
    - _Requirements: 4.2, 4.3, 4.4, 4.7, 8.10_

  - [ ] 26.2 Implement `TakeoverAlertPresenter`
    - `raise(callerNumber, reason)` posts the `takeover_alert` notification with a full-screen intent to `LiveCallActivity` and one Take over action (a `PendingIntent` to `CallForegroundService` sending `UserTakeOver(origin = TAKEOVER_ALERT)`); heads-up fallback without full-screen intent access; text notes when the Caller may be on hold
    - Alert tone through `AgentAudioPlayer` every 10 s offset by 5 s from "please wait", stopped after 30 s; notification kept until `clear()` on take over or call end
    - _Requirements: 4.2, 4.5, 4.7, 4.8, 4.9, 4.10, 4.11, 13.21_

  - [ ]* 26.3 Write Robolectric tests for the Takeover_Alert
    - Full-screen intent and heads-up fallback, Take over action sends `UserTakeOver`, tone schedule with a fake clock, alert kept after 30 s until call end
    - _Requirements: 4.8, 4.9, 13.21_

- [ ] 27. Implement VAD, ASR and TTS adapters with bundled models
  - [ ] 27.1 Implement `SileroVad` and the segmenter
    - Silero VAD through sherpa-onnx with an energy-VAD fallback; 400 ms endpoint hangover; segments passed through `EchoGate`; `VoiceActivity` and `noAudioForMs` reported to the engine tick
    - _Requirements: 2.1, 8.7, 8.8, 13.7_

  - [ ] 27.2 Implement the speech recognizer for the S7-chosen engine
    - `SpeechRecognizerPort` implementation selected by `BuildConfig.ASR_ENGINE`: `WhisperRecognizer` (base int8, 2 threads, per-utterance decode) or `VoskRecognizer` (small `hi` and `en-in`, streaming); 3 s timeout gives an empty result; `SystemRecognizer` as last resort; only the chosen models bundled, memory-mapped, loaded per call and released at call end
    - _Requirements: 2.1, 2.9, 2.10, 8.2, 8.6, 8.11_

  - [ ] 27.3 Implement `SherpaPiperSynthesizer`, `ScriptSplitter` and `AndroidTtsSynthesizer`
    - Bundled Piper voices (Hindi female and male, English female and male) through sherpa-onnx, only the two voices of the frozen gender loaded per call; `ScriptSplitter` for mixed-script text with the same gender per run; Android `TextToSpeech` fallback with `VoiceSelector` and substitution reporting; first sentence synthesized first and later sentences while it plays
    - _Requirements: 7.8, 7.10, 7.11, 7.12, 7.13, 7.15, 8.11, 13.14_

  - [ ] 27.4 Generate and bundle the fallback phrase WAVs
    - Gradle task that renders the fallback, filler, please-wait and nobody-available phrases with the bundled voices (male and female, Hindi and English) into `assets/fallback/`; `FallbackPhrases` loader returning PCM for the frozen gender and language
    - _Requirements: 4.7, 5.10, 7.14, 8.9, 8.11_

  - [ ]* 27.5 Write tests for the speech adapters
    - JVM unit tests for `ScriptSplitter`; instrumented tests for synthesis with network off for both genders and languages, fallback WAV lookup, and ASR timeout giving an empty result
    - _Requirements: 2.10, 7.14, 7.15, 8.11_

- [ ] 28. Implement the network monitor and the optional cloud LLM client
  - [ ] 28.1 Implement `NetworkMonitor`
    - HTTPS probe every 500 ms during a call only (no User data), `ConnectivityManager.NetworkCallback` for immediate loss, `StateFlow<NetworkQuality>` from `NetworkClassifier` at least every 2 s
    - _Requirements: 8.1, 8.2, 8.5_

  - [ ] 28.2 Implement `CloudLlmClient` and `StallingResponder`
    - `CloudLlmClient` (off by default, HTTPS, Keystore-backed key, no-retention request option, nothing logged); `StallingResponder` in `:app` that renders the local template first, then calls the cloud through `ProviderRouter` with `cloudDeadlineMs`, validates with `PiiGuard.acceptReply`, and falls back to the local reply silently
    - _Requirements: 5.2, 5.4, 5.7, 8.3, 8.4, 10.7_

  - [ ]* 28.3 Write unit tests for the stalling responder
    - Fake cloud: in-time success, timeout, error, reply with a protected value rejected, cloud disabled never called
    - _Requirements: 5.4, 8.4, 10.7_

- [ ] 29. Wire the call pipeline in `CallForegroundService` and build the Live_Call_Screen
  - [ ] 29.1 Wire capture to engine in `CallForegroundService`
    - On answer: `AudioRouteController`, capture, VAD, `EchoGate`, ASR, `EchoTextFilter`, `ScriptLanguageDetector` (ASR tag as fallback), engine `CallerUtterance` with the device local time to the minute (null when unreadable); 1 s `Tick` loop with silence and no-audio counters; `NetworkMonitor` started for the call; every exception converted into an engine event at the service boundary
    - _Requirements: 1.4, 2.1, 7.1, 8.2, 8.5, 8.6, 11.5, 11.15, 11.17, 13.5, 13.15_

  - [ ] 29.2 Implement the effect executor and `MapSmsSender`
    - Execute `Speak` (`ResponseRenderer` or `StallingResponder`, TTS, player, `AgentSpeechFinished`; fallback WAVs on failure), `StopSpeaking`, `StartTransfer` (`TransferController`, then `TransferResult`), `RaiseTakeoverAlert`, `ClearTakeoverAlert`, `SendMapSms`, `Notify`, `EndCall`, `LogNote`
    - `MapSmsSender` with sent and delivered `PendingIntent`s, 30 s delivery timeout and failure notification within 60 s; on-device translation through ML Kit when the model is present, for Delivery_Steps and quick replies
    - _Requirements: 3.1, 3.4, 3.8, 4.2, 4.7, 7.9, 8.9, 9.19, 13.6, 13.10, 13.12, 13.14_

  - [ ] 29.3 Implement call-end finalization
    - On `CallEnded`: build the entry with `CallLogEntryBuilder`, save within 2 s with retries, schedule `RecordingLinker` (or NO_FOLDER_SELECTED), post unknown-call, silent-caller and post-call spam notifications (within 30 s, SOLICITATION only), clear the Takeover_Alert, restore volume, release models, stop the service
    - _Requirements: 5.9, 6.3, 6.6, 6.7, 10.1, 10.3, 10.12, 12.1, 12.7, 13.18, 14.2, 14.3_

  - [ ] 29.4 Build the Live_Call_Screen and live call notification
    - Compose screen in `LiveCallActivity` from shared components: transcript list with `TranscriptBubble` (updated within 2 s), intent and language chips, Take over, Mute Agent, Resume Agent, End call, Quick_Reply chips and a text field, "You are talking" banner in Take_Over, assisted-mode banner
    - Live call notification within 2 s of answer with Take over, Mute/Resume, End call actions
    - _Requirements: 13.4, 13.5, 13.6, 13.10, 13.11, 13.12, 13.13, 13.14, 13.19_

  - [ ]* 29.5 Write integration tests for the call pipeline
    - Fake capture, ASR and TTS ports driving the service: greeting within 2 s, delivery flow, transfer to Takeover_Alert, take over from the alert and from the screen giving the same log record, mute and resume, end call within 2 s, Call_Log saved with all fields
    - _Requirements: 1.4, 4.8, 10.1, 13.6, 13.12, 13.21_

- [ ] 30. Implement recording linking and playback
  - [ ] 30.1 Implement `RecordingFolderRepository`
    - SAF folder selection contract, `takePersistableUriPermission`, candidate listing with `DocumentFile` (name, MIME, size, last modified, name-parsed start time per S5), existence check, `DocumentsContract.deleteDocument` after confirmation; `SecurityException` mapped to FOLDER_ACCESS_LOST; read-only access, no copies
    - _Requirements: 9.2, 9.11, 12.9, 12.10, 12.11, 12.12, 12.14_

  - [ ] 30.2 Implement `RecordingLinker`
    - WorkManager jobs at 0, 20, 40 and 60 s after call end using `RecordingMatcher` and `CallLogDao.linkRecording`; LINKED with uri, duration and size within 60 s; NOT_FOUND after the last retry with one notification per call; FOLDER_ACCESS_LOST with a notification; manual "Search again"
    - _Requirements: 12.1, 12.2, 12.3, 12.4, 12.5, 12.6, 12.7, 12.11, 12.13, 12.15, 12.16_

  - [ ] 30.3 Implement `PlaybackController` with Media3
    - ExoPlayer from the `content://` URI with play, pause, resume and stop, no network
    - _Requirements: 12.8, 12.13_

  - [ ]* 30.4 Write instrumented tests for recording linking
    - Test `DocumentsProvider`: link within the window, retries, revoke, delete, file removed outside the App, playback controls
    - _Requirements: 12.3, 12.5, 12.9, 12.10, 12.11_

- [ ] 31. Implement the spam reporter
  - [ ] 31.1 Implement `SmsIntentLauncher`, `SmsGateway`, `SmsSentReceiver` and `CellServiceState`
    - `SmsIntentLauncher.openComposer(body)`: `ACTION_SENDTO sms:1909` with `sms_body`, false on `ActivityNotFoundException`
    - `SmsGateway.sendToTrai(parts, sent)` with the destination fixed to `TraiReport.NUMBER`, `divideMessage` and multipart send, compiled out of use when `SILENT_SMS_ENABLED` is false
    - `SmsSentReceiver` mapping result codes per part; `CellServiceState.inService()` from `ServiceState`; 60 s `SilentSendTimeout` WorkManager job
    - _Requirements: 14.4, 14.14, 14.15, 14.16, 14.18, 14.19, 14.22, 14.23, 14.24, 14.28, 14.30_

  - [ ] 31.2 Implement `SpamReporter`
    - Per-entry `Mutex`, settings read per report, `ReportFlowPolicy` and `ReportStatusMachine.reduce`, action execution, `casReport` writes within 2 s with retries; "Sent to 1909: <text>" notification within 5 s on the silent path; nothing logged, no Outbox, no network
    - _Requirements: 9.26, 14.3, 14.5, 14.13, 14.14, 14.16, 14.17, 14.18, 14.20, 14.21, 14.25, 14.26, 14.27, 14.28_

  - [ ] 31.3 Implement `ReportSpamActivity` and the entry points
    - Dialog-themed activity built from `AppDialog`, `AppTextField`, `AppButton`: message shown within 2 s, editable Brief_Description with the validation message, Confirm and Cancel, repeat-confirmation dialog, cannot-report and no-messaging-app messages, permission note
    - Wire the `post_call` Report spam action to this activity with an activity `PendingIntent` (no trampoline)
    - _Requirements: 14.1, 14.2, 14.5, 14.9, 14.13, 14.17, 14.19, 14.20, 14.26_

  - [ ]* 31.4 Write tests for the spam reporter
    - Robolectric: double tap from notification and Call_Log gives one hand-off, cancel restores status, no messaging app gives FAILED, silent path with airplane mode gives FAILED and a retry offer, `SmsManager` never called with the silent setting off
    - _Requirements: 14.15, 14.19, 14.20, 14.24, 14.27_

- [ ] 32. Build the remaining screens
  - [ ] 32.1 Build onboarding, permissions and Home
    - Navigation graph; onboarding with purpose text before each request: call screening role, `READ_CONTACTS`, `ANSWER_PHONE_CALLS` and `READ_PHONE_STATE`, `CALL_PHONE`, `RECORD_AUDIO`, `POST_NOTIFICATIONS`, `SEND_SMS`, accessibility service (with restricted-settings help), full-screen intent access, battery unrestricted, Recording_Folder picker, one-time auto-record instruction; no broad storage permission
    - Home with screening status, denied-permission list with "Request again" and "Open settings", and a `StatusBanner` within 2 s naming the disabled feature and permission
    - _Requirements: 9.2, 9.3, 9.7, 9.11, 9.12, 9.13_

  - [ ] 32.2 Build the settings screens
    - General (screening, Map_Location, Transfer_Target, max stalling, protected terms, cloud LLM toggle), gates and Night_Window, disclosure, voice with sample playback, speaker, quick replies editor, recording folder with warning when none is selected and the legal note, spam reporting (silent send switch with permission hint, read-only "Reports are sent to 1909 (TRAI)")
    - Inline validation messages and previous values kept on rejection through `SettingsStore.update`
    - _Requirements: 1.7, 9.1, 9.4, 9.6, 9.8, 9.9, 9.10, 9.11, 9.13, 9.14, 9.15, 9.25, 9.26, 13.3, 13.13, 14.30_

  - [ ] 32.3 Build the Delivery_Steps editor
    - Add, edit, delete and reorder steps with English and Hindi fields, missing-language message on save, limits validation, reset to defaults, and a control that plays the full Delivery_Directions sample in the selected voice and language
    - _Requirements: 9.16, 9.17, 9.18, 9.20, 9.21, 9.22_

  - [ ] 32.4 Build the Call_Log, call detail and player screens
    - Call_Log list in reverse chronological order with report status chip and "Report spam" (and "Retry report" for FAILED) on every row
    - Call detail: all entry fields, transcript, gate statements, takeovers, recording status ("recording unavailable" when the file is gone), player controls, delete entry with a follow-up "Also delete the recording?" dialog defaulting to no
    - _Requirements: 10.4, 10.5, 12.8, 12.9, 12.10, 14.1_

  - [ ]* 32.5 Write Compose UI tests for the screens
    - Onboarding order and denial banners, settings validation messages, Delivery_Steps editor (add, edit, delete, reorder, reset), Live_Call_Screen controls and transcript updates, Call_Log order and delete dialog
    - _Requirements: 9.3, 9.4, 9.6, 9.16, 9.20, 10.4, 10.5, 13.4, 13.5_

- [ ] 33. Checkpoint - Integration complete
  - Run `./gradlew test` and the instrumented tests on the M34 5G. Ensure all tests pass, ask the user if questions arise.

- [ ] 34. Validate on the Galaxy M34 5G
  - [ ] 34.1 Add the patchy-network test harness and run the matrix
    - Shell scripts for a laptop hotspot with `tc netem` (good, 1,800 ms RTT, 15% loss, flapping every 5 s) and a debug-build overlay on the Live_Call_Screen showing `NetworkQuality`, reply source (LOCAL or CLOUD) and response latency
    - Ask the User to run the manual matrix from design.md (four intents in Hindi, English and Hinglish, Take over, Mute, Resume, End call, quick replies) and record results in design.md
    - _Requirements: 8.1, 8.2, 8.3, 8.4, 8.5, 8.6, 8.10, 8.12_

  - [ ] 34.2 Write and run the airplane-mode instrumented suite
    - Network off: classification, language detection, gate info, synthesis with bundled voices for both genders, fallback WAVs, recording link and playback, spam report hand-off
    - _Requirements: 2.9, 7.15, 8.11, 11.15, 12.13, 14.22_

  - [ ] 34.3 Add the end-to-end scenario script and run it with the User
    - A scenario checklist file and a debug "export call trace" action (event and effect timeline with timings) to check the 1 s, 2 s, 3 s and 500 ms limits
    - Ask the User to place calls from a second phone for delivery, door not opened (Path A if enabled, Path B), solicitation and unknown in Hindi, English and Hinglish, including gate queries during and outside the Night_Window, and record results in design.md
    - _Requirements: 1.1, 1.2, 1.4, 2.1, 3.1, 4.2, 5.2, 7.1, 11.1, 12.3, 13.1, 13.5, 13.6, 13.10, 13.19_

- [ ] 35. Final checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional and can be skipped for a faster MVP. Core implementation tasks are never optional.
- Task 2 is a hard gate: if S2 or S3 fails, stop and ask the User to choose F1 or F2 before any production code.
- The `:spike` module is throwaway code; production modules do not depend on it.
- Each property test implements exactly one Correctness Property from design.md, runs at least 100 iterations (1,000 for classifier and gate properties) and carries the tag `// Feature: ai-call-screening-agent, Property N: <title>`.
- Properties 25 and 47 are split into a JSON part in `:core` (tasks 20.2, 20.3) and an entity-mapper part in `:app` (tasks 22.2, 22.3).
- The transfer controller and Takeover_Alert (task 26) come after the audio path because both use the accessibility service and the Agent audio player.
- All screens use `ui/components` and `ui/theme` only; a missing variant is added to the shared component, not created locally.

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1"] },
    { "id": 1, "tasks": ["1.2", "1.3", "1.4", "1.6", "1.8", "1.9"] },
    { "id": 2, "tasks": ["1.5", "1.7"] },
    { "id": 3, "tasks": ["3.1"] },
    { "id": 4, "tasks": ["3.2", "4.1"] },
    { "id": 5, "tasks": ["3.3", "4.2", "5.1", "6.1", "7.1", "9.1", "10.1", "11.1", "12.1", "13.1"] },
    { "id": 6, "tasks": ["4.3", "4.4", "5.2", "5.3", "6.2", "6.3", "6.4", "6.5", "7.2", "7.3", "8.1", "9.2", "10.2", "10.3", "11.2", "12.2", "12.3", "13.2", "13.3", "13.4", "14.1"] },
    { "id": 7, "tasks": ["6.6", "8.2", "8.3", "9.3", "9.4", "9.5", "14.2", "14.3", "14.4", "14.5", "14.6", "14.7", "15.1"] },
    { "id": 8, "tasks": ["14.8", "14.9", "14.10", "14.11", "14.12", "15.2"] },
    { "id": 9, "tasks": ["15.3", "15.4", "15.5", "15.6", "15.7", "16.1"] },
    { "id": 10, "tasks": ["16.2", "16.3", "17.1"] },
    { "id": 11, "tasks": ["17.2", "17.3", "17.4", "17.5", "18.1"] },
    { "id": 12, "tasks": ["18.2", "18.3", "18.4", "18.5", "19.1"] },
    { "id": 13, "tasks": ["19.2", "19.3", "19.4", "19.5", "19.6", "20.1"] },
    { "id": 14, "tasks": ["20.2", "20.3", "20.4", "20.5", "22.1", "22.4", "23.1", "24.1"] },
    { "id": 15, "tasks": ["22.2", "22.3", "22.5", "22.6", "23.2", "25.1"] },
    { "id": 16, "tasks": ["22.7", "23.3", "24.2", "25.2", "25.3", "27.1", "28.1"] },
    { "id": 17, "tasks": ["24.3", "25.4", "26.1", "26.2", "27.2", "27.3", "28.2"] },
    { "id": 18, "tasks": ["26.3", "27.4", "28.3", "30.1"] },
    { "id": 19, "tasks": ["27.5", "29.1", "30.2"] },
    { "id": 20, "tasks": ["29.2", "30.3"] },
    { "id": 21, "tasks": ["29.3", "31.1"] },
    { "id": 22, "tasks": ["29.4", "30.4"] },
    { "id": 23, "tasks": ["29.5", "31.2"] },
    { "id": 24, "tasks": ["31.3", "32.1"] },
    { "id": 25, "tasks": ["31.4", "32.2"] },
    { "id": 26, "tasks": ["32.3"] },
    { "id": 27, "tasks": ["32.4"] },
    { "id": 28, "tasks": ["32.5"] },
    { "id": 29, "tasks": ["34.1", "34.2", "34.3"] }
  ]
}
```
