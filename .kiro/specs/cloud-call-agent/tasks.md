# Implementation Plan: Cloud Call Agent

## Overview

The plan is in Kotlin (JVM for the server, Android for the Companion_App) and AWS SAM for infrastructure, as in the design. It runs in these stages:

0. Tasks the User must do. They are marked **USER** and have long lead times (Exotel KYC, DLT approval, DNS), so they start first.
1. Seven spikes (C-6, C-4, C-5, C-1, C-7, C-2, C-3) that settle the facts the design depends on, followed by a go/no-go checkpoint. No production code is written before the checkpoint passes.
2. The new Gradle module `:agent` (reusing `:core`), the SAM template in `infra/cloud-agent/`, secrets, and IAM.
3. The Exotel stream handler, codec, resampler, pacer, and `clear` handling.
4. The Nova 2 Sonic client: bidirectional stream, tools, interruption, and session renewal.
5. The seven Agent_Tools on the `:core` rules.
6. The call policy, the takeover state machine, and the fallbacks.
7. Persistence (DynamoDB, S3 recordings, retention sweeper), traces, and cost.
8. The Companion_API, FCM push, and the SMS fallback.
9. The Companion_App changes, including the 16 KB page-size compatibility that the app keeps.
10. Deployment to ap-northeast-1 and end-to-end validation with real calls.

The product is multi-user (Requirements 25 to 31). Multi-user work is spread over the stages: tenant keys and the `admins` group (0 and 3), tenant resolution, the versioned settings store, and the isolating data access layer (9), the Companion_API routes for settings, devices, and admin (10), and onboarding and settings sync in the app (12). New sub-tasks have the next free number in their group, so no existing number changes.

Each of the 28 correctness properties is a property-based test (Kotest, at least 100 iterations, 500 for the policy, pacer, and PII guard), tagged `// Feature: cloud-call-agent, Property N: <title>`, and is placed right after the code it covers.

Decisions used by the plan (see "Approved decisions, domain, and defaults" in design.md):
- Domain `leadingdots.com`: `agent.leadingdots.com` for the Exotel stream and webhooks, `api.leadingdots.com` for the Companion_API, on the same ALB with host-based listener rules. One ACM certificate in ap-northeast-1 with DNS validation.
- Defaults the User can change: FCM direct, Exotel feature enablement to be confirmed with Exotel support, an Exotel-provided DLT template, voices `kiara` and `arjun` (alternatives `tiffany` and `matthew`), and the existing Cognito pool in ap-south-1.
- Server-side recording starts after the spoken notice and the Take_Over segment is not recorded. The takeover dial timeout is 15 seconds, and calls from the Virtual_Number or the User_Mobile are rejected. Retention is enforced by the sweeper with a 366-day S3 lifecycle backstop.

Tasks marked **USER** are done by the User, not by a coding agent. A coding agent should skip them and ask the User for the result.

## Tasks

- [ ] 0. Prerequisites the User must do
  - [~] 0.1 **USER** Complete the Exotel account setup: KYC, Virtual_Number, and plan
    - Complete Exotel KYC and obtain Indian Virtual_Numbers: at least two (one for the owner, one for a test User) so that the multi-user tests can run
    - Ask Exotel for the monthly price per ExoPhone, the KYC effort per extra number, and any limit on numbers or concurrent streams per account (requirements open question 15)
    - Record the account SID, API key, API token, subdomain, and the plan and trial limits in a private note (not in the repository)
    - Ask Exotel to start the DLT process for the Map_SMS and alert SMS (see 1.2)
    - _Requirements: 1.1, 10.5, 11.8_

  - [~] 0.2 **USER** Confirm Exotel feature enablement with Exotel support
    - Ask for the Voicebot (AgentStream) applet, the Passthru and Connect applets with dynamic URLs, and the Legs and Bridge APIs to be enabled on the account
    - Ask whether the Voicebot applet fails over to the next applet when the WebSocket cannot connect, and which source IP ranges Exotel uses
    - Record the answers; spikes C-1 and C-3 depend on them
    - _Requirements: 1.1, 5.2, 11.4, 16.11_

  - [~] 0.3 **USER** Create the Firebase project for push notifications
    - Create a Firebase project and add the Android app with the application id of `:app`
    - Download `google-services.json` (kept out of git, see 12.7) and create a service account key for FCM HTTP v1 (stored in Secrets Manager in 14.3)
    - The repository has a Firebase power available that can help with this step
    - _Requirements: 11.8, 19.8_

  - [x] 0.4 **USER** Check where the DNS of `leadingdots.com` is hosted
    - Run `aws route53 list-hosted-zones --profile ldots` and look for `leadingdots.com.`
    - If a hosted zone exists, record its zone id (used by 3.3 and 14.1)
    - If no hosted zone exists, find the registrar or DNS host and plan to create the ACM validation CNAMEs and the two host name CNAMEs by hand in 14.1
    - _Requirements: 16.1_

  - [x] 0.5 **USER** Check Bedrock model access for Nova 2 Sonic in ap-northeast-1
    - Run `aws bedrock list-foundation-models --by-provider amazon --region ap-northeast-1 --profile ldots` and confirm that `amazon.nova-2-sonic-v1:0` is listed
    - Run `aws bedrock get-foundation-model-availability --model-id amazon.nova-2-sonic-v1:0 --region ap-northeast-1 --profile ldots` and confirm that the authorization and entitlement are available
    - Run `aws bedrock list-inference-profiles --region ap-northeast-1 --profile ldots` and record whether an inference profile is required
    - If access is not granted, request model access in the Bedrock console and record the result
    - _Requirements: 4.7_

  - [x] 0.6 **USER** Create the Cognito group `admins` (sign-up policy is resolved: admin-created accounts only)
    - In the existing Cognito user pool (ap-south-1) create the group `admins` (`aws cognito-idp create-group --group-name admins --user-pool-id <pool> --region ap-south-1 --profile ldots`) and add the owner's account to the group
    - Sign-up policy is resolved (requirements open question 17): accounts are created by the Admin only, and no public self sign-up. Task 0.7 enforces it
    - _Requirements: 26.6, 26.7_

  - [x] 0.7 **USER** Disable self sign-up for the Cognito app client
    - If the user pool allows it, set `AdminCreateUserConfig.AllowAdminCreateUserOnly=true` on the existing pool (`aws cognito-idp update-user-pool --user-pool-id <pool> --admin-create-user-config AllowAdminCreateUserOnly=true --region ap-south-1 --profile ldots`, after reading the current pool settings with `describe-user-pool` so that no other setting is reset), and confirm that a self sign-up attempt with the app client is rejected
    - If the pool does not allow it, record that, and rely on the Companion_API creating a profile with the Agent off and no number (no paid resources are used without an assignment)
    - Create each User's account with `aws cognito-idp admin-create-user`
    - Self-service account deletion and data export stay out of scope for this release (requirements open question 19)
    - _Requirements: 26.1, 26.6_

- [ ] 1. Run the feasibility spikes
  - [x] 1.1 Create the `:spike-cloud` JVM module and the shared spike harness
    - Add a Gradle JVM module `:spike-cloud` (not a dependency of `:app` or `:agent`) with the AWS SDK for Java v2 `bedrockruntime`, Ktor server and client with WebSockets, and Kotest
    - Add a `SpikeLog` that writes timestamped JSON lines to `spike-cloud/results/` and a `Frames` recorder that stores raw Exotel and Sonic frames as fixtures for later contract tests
    - Add `docs/cloud-spikes.md` with one results table per spike (C-6, C-4, C-5, C-1, C-2, C-3)
    - _Requirements: 24.1_

  - [~] 1.2 Run spike C-6: Exotel SMS and the DLT template
    - Request the DLT entity, sender ID, and template approval through Exotel (template with a link variable for the Map_SMS and a second one for the Takeover alert SMS)
    - Send a test SMS with the link as a template variable to an Indian mobile through the Exotel SMS API with a small script in `:spike-cloud`
    - Record go or no-go: an approved template delivers the SMS within 30 seconds; no-go removes Map_SMS and leaves a User decision on Requirement 10 criterion 5
    - _Requirements: 10.5, 10.8, 11.8_

  - [~] 1.3 Run spike C-4: Airtel forwarding behavior (manual, on the User's phone)
    - Set `**61*<Virtual_Number>**20#`, `**67*<Virtual_Number>#`, and `**62*<Virtual_Number>#` on the User_Mobile
    - From another phone, call the User_Mobile with the phone unanswered, busy, and in airplane mode, each with mobile data and with Wi-Fi calling
    - Record whether each forwards, whether the ring time is honored, and whether the Exotel `From` equals the Caller number; update `docs/cloud-spikes.md`
    - _Requirements: 1.3, 2.1, 2.5, 2.8_

  - [x] 1.4 Run spike C-5: Nova 2 Sonic session behavior in ap-northeast-1
    - Write a scripted session driver that follows the event sequence of the design (sessionStart, promptStart with tools, system content, history, audio start) and streams recorded audio
    - Measure session length and the 8-minute limit, replacement with history, tool call round trips, the `INTERRUPTED` signal delay, speculative-text-before-audio ordering, injected text instructions (10 trials), cross-modal greeting trigger, idle timeout, the SDK version, and the model or inference profile identifier
    - Save the raw events as fixtures for 5.1 and record go or no-go per the design table
    - _Requirements: 4.4, 5.1, 12.8, 14.1, 14.2, 14.8_

  - [~] 1.5 Run spike C-1: Exotel stream protocol with an echo server behind the ALB
    - Add `infra/cloud-agent/spike-echo.yaml` (VPC, ALB with ACM certificate for `agent.leadingdots.com`, one Fargate task, no other resources) and an echo WebSocket server in `:spike-cloud` that logs every frame
    - Configure the Exotel flow with the Voicebot applet and test `sample-rate` 8000, 16000, and 24000; record the start event fields, chunk sizes, `mark`, `clear` (removal within 200 ms), DTMF, applet transitions on failure, the Basic auth header, a hidden Caller number, and the Passthru status branching
    - Also record the `To`, `From`, and any `ForwardedFrom` or diversion fields that the Passthru and Voicebot start events carry
    - Save the frames as fixtures for 4.1 and update `docs/cloud-spikes.md`
    - _Requirements: 1.2, 1.3, 3.1, 5.2, 16.2, 16.11, 25.1_

  - [~] 1.6 Run spike C-2: Sonic quality for Hindi, English, and Hinglish
    - Replay the 30-utterance corpus (`src/main/assets/s7/wav`) resampled to 8 kHz, as 8 kHz and as upsampled 16 kHz input, with voices `kiara`, `arjun`, `tiffany`, and `matthew`
    - Measure intent accuracy, transcript quality, endpointing sensitivity (`MEDIUM` and `HIGH`), the transcript script setting, and Reply_Latency; then run 10 live calls through Exotel
    - Record the voice the User accepts by listening (defaults `kiara` and `arjun`)
    - _Requirements: 4.2, 7.1, 7.3, 8.9_

  - [~] 1.7 Run spike C-3: takeover mechanism
    - Build the Voicebot, then Connect (dynamic URL), then loop-back flow and test a User_Mobile answer, busy, no answer within the 15-second dial timeout, and the `resume=1` return to the Voicebot; test that no forwarding loop occurs
    - If the loop does not work, test alternative B (Legs and Bridge APIs)
    - Record go or no-go: the Caller hears the User_Mobile within 10 seconds of the tap and a failed dial returns to the Agent within 5 seconds
    - _Requirements: 11.4, 11.5, 11.7, 11.10_

  - [~] 1.8 Run spike C-7: forwarding hint and `To` behavior with two numbers
    - Forward two test phones (the User_Mobile of the owner and of a test User) to two different ExoPhones with `**61*`, `**67*`, and `**62*`
    - Place 10 calls per condition (unanswered, busy, unreachable) and log the `To`, `From`, `ForwardedFrom`, and any diversion header that Exotel exposes in the Passthru request and in the Voicebot start event
    - Record go or no-go in `docs/cloud-spikes.md`: `To` equals the ExoPhone in every call (go); whether a hint is present and equals the forwarding phone's number; the model does not change if no hint exists
    - _Requirements: 25.1, 25.5, 25.6, 25.7_

- [~] 2. Checkpoint - Spike go/no-go
  - Review `docs/cloud-spikes.md` with the User and copy each result into a new "Spike results" subsection of design.md
  - Apply the no-go consequences of the design table: no Map_SMS (C-6), extended Wi-Fi calling text (C-4), small chunks and 100 ms send-ahead (C-1), fixed-audio prompts or look-ahead PII buffer (C-5), English-only Agent (C-2), Bridge APIs or fixed failure message (C-3)
  - If a no-go changes a requirement, ask the User to approve the change before task 3 starts
  - Ensure all tests pass, ask the user if questions arise.

- [ ] 3. Set up the `:agent` module, the infrastructure, and secrets
  - [x] 3.1 Create the `:agent` Gradle module
    - JVM module with Ktor (Netty), kotlinx-serialization, kotlinx-coroutines, AWS SDK for Java v2 (`bedrockruntime`, `dynamodb`, `s3`, `secretsmanager`, `cloudwatch`), Kotest, and a dependency on `:core`; register it in `settings.gradle.kts`
    - Add a `Clock` interface, a `Config` loader that reads environment variables (region, table, bucket, secret names, `sonic.maxSessionSeconds`, `sonic.idleSeconds`, model identifier), and a `/health` route
    - Add one smoke test so `./gradlew :agent:test` runs
    - _Requirements: 24.1, 14.1_

  - [x] 3.2 Add the cloud fields and the `cloud` package to `:core`
    - Add the `Settings` fields (including `userMobile`, `voiceGender`, `voiceId`, and the server-owned `version`), `CallLogEntry` fields (including `settingsVersion`), and the `cloud` package skeleton listed in the design; every added field has a default value
    - Extend `SettingsValidator` with the `userMobile` rule (E.164) and a hook for the cross-record rule that `userMobile` is not an assigned Virtual_Number
    - Verify that the existing `:core` tests and serialized fixtures still pass unchanged
    - _Requirements: 20.3, 18.1, 24.2, 27.1, 27.7_

  - [x] 3.3 Write the SAM template: network, load balancer, certificate, and service
    - Create `infra/cloud-agent/template.yaml` (consistent with `lambda/polly_tts/template.yaml`) with parameters `CognitoUserPoolId`, `CognitoClientId`, `HostedZoneId` (optional), `ImageUri`, and the two domain names
    - Define the VPC with public subnets, the ALB (idle timeout 3600 s), the ACM certificate for `agent.leadingdots.com` and `api.leadingdots.com` with DNS validation, the HTTPS listener with host-based rules, and the Route 53 validation and ALIAS records when `HostedZoneId` is given
    - Define the ECR repository, the ECS cluster and service (`desiredCount` 1, 100 and 200 percent deployment, circuit breaker, `stopTimeout` 120 s, 0.5 vCPU and 1 GB), the security groups (port 443 from the ALB only), and the log group
    - _Requirements: 4.7, 16.1, 1.1_

  - [~] 3.4 Write the SAM template: data, secrets, IAM, and alarms
    - Add the KMS key, the DynamoDB table `CallAgent` (`pk`, `sk`, TTL `ttl`) with the global secondary index `gsi1` (`gsi1pk`, `gsi1sk`), the S3 bucket `callagent-recordings-<account>-ap-northeast-1` (block public access, KMS, 366-day lifecycle backstop, keys under `users/<sub>/`), and the Secrets Manager entries (Exotel account, stream Basic credentials, token HMAC secret, FCM service account)
    - Add the task role with the least-privilege statements of the design (Bedrock bidirectional stream on the Sonic model and profile, the table and the `gsi1` index, the bucket, one key, logs, metrics) and the execution role (secrets, image pull)
    - Add the configuration parameters `limits.perUser` (3) and `limits.global` (10), and remove any single-household parameter such as a configured `sub`
    - Add the `FallbackCount` alarm with an SNS topic and e-mail subscription, and a `CapRefusals` metric
    - _Requirements: 16.3, 16.4, 16.5, 17.5, 23.7, 28.4, 29.1, 29.2_

  - [x] 3.5 Add the container build and push scripts
    - Add a Dockerfile (or Gradle Jib configuration) for `:agent` with a JRE 21 base and the SIGTERM drain handler (stop accepting new calls, let calls finish up to 120 s)
    - Add `scripts/build-push-agent.sh` that builds the image and pushes it to the ECR repository with `--profile ldots --region ap-northeast-1`
    - _Requirements: 1.4, 4.7_

  - [ ]* 3.6 Write smoke tests for the template
    - Run `sam validate --lint` and `cfn-lint` in a test script
    - Add a snapshot test of the IAM statements that fails on a wildcard resource outside an allowed list
    - _Requirements: 16.3, 16.4_

  - [x] 3.7 Add the `tenant` package to `:core`
    - Add `UserId` and `E164` value types, `PhoneNumbers.normalize` (pure; handles `+91`, `91`, a leading `0`, spaces, hyphens, and 10-digit Indian numbers; returns null for non-numbers; idempotent), and `Keys` that build every DynamoDB key (`USER#<sub>`, `NUMBER#<e164>`, `CALL#...`, `DEVICE#...`) and S3 key (`users/<sub>/...`) from a `UserId`
    - Add `SettingsMerge.threeWay(base, server, edits)` and the `SettingsUpdate` result types (`Saved`, `Conflict`, `Invalid`)
    - _Requirements: 25.1, 25.10, 27.10, 28.4, 28.5_

- [ ] 4. Implement the Exotel stream handler and the audio path
  - [x] 4.1 Implement `ExotelMessages` and `PcmCodec`
    - JSON codec for connected, start, media, mark, clear, dtmf, and stop events; base64 and PCM16 conversion; frame validation against the announced `media_format`
    - Discard and count a frame whose length or encoding does not match, without changing later frames
    - _Requirements: 3.1, 3.5, 3.7_

  - [ ]* 4.2 Write property test for the Exotel codec
    - **Property 14: Exotel message codec round trip**
    - **Validates: Requirements 3.1, 3.5, 3.7**

  - [x] 4.3 Implement `Resampler`
    - Integer-ratio resampling (8 to 16 kHz, 24 to 8 kHz, generic ratios) with a linear-phase FIR low-pass filter and clipping to the 16-bit range; rates come from the start event and configuration
    - _Requirements: 3.2, 3.3, 3.6_

  - [ ]* 4.4 Write property test for the resampler
    - **Property 15: Resampling keeps duration and bounded amplitude**
    - **Validates: Requirements 3.2, 3.3, 3.6**

  - [~] 4.5 Implement `OutboundPacer` with chunking and `clear`
    - Outbound queue, 3200-byte chunks (multiples of 320 bytes, last chunk padded with silence), at most 300 ms of send-ahead, `mark` after each utterance, and queue flush with exactly one `clear` event on interruption or PII hit within 200 ms
    - Drop later audio of an interrupted content until its end; use small chunks and a 100 ms send-ahead if spike C-1 requires it
    - _Requirements: 3.3, 3.4, 5.1, 5.2, 12.9_

  - [ ]* 4.6 Write property test for the pacer
    - **Property 16: Pacer chunking, rate, and barge-in clear**
    - **Validates: Requirements 3.3, 3.4, 5.1, 5.2, 12.9**

  - [x] 4.7 Implement `SilenceDetector`
    - Short-term energy detector on Caller audio; silence counts from the `mark` that reports the last Agent audio as played
    - _Requirements: 6.5, 13.2_

  - [x] 4.8 Implement `ExotelAuth` and the call token
    - HTTP Basic check for the WebSocket handshake and the `/exotel/*` webhooks against Secrets Manager values
    - HMAC-SHA256 call token over `CallSid`, the User_Id, and a 60-second expiry, with constant-time comparison; verification returns the User_Id
    - _Requirements: 16.2, 16.3, 16.11, 25.9_

  - [ ]* 4.9 Write property test for call tokens
    - **Property 21: Call token verification**
    - **Validates: Requirements 16.2, 16.3, 16.11, 25.9**

  - [~] 4.10 Implement `ExotelRoutes` and `ExotelStreamHandler`
    - Routes `/exotel/route`, `/exotel/resolve`, `/exotel/next`, `/exotel/passthru`, and the `/stream/<callToken>` WebSocket, with ports for the tenant resolver, the route decision, the call session factory, and the test-call registrar
    - `/exotel/route` and `/exotel/resolve` read the `To` number and call the tenant resolver; an unresolved number answers with the Out_Of_Service branch; the stream handler rejects a token whose User_Id differs from the assigned User_Id
    - `/exotel/next` takes the Take_Over destination from the call state of the owning User and never from a request parameter
    - Record the Caller number from the start event (or `unknown` when hidden), end the call after 10 seconds without media and record the interruption, and close the stream on stop
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 3.8, 16.3, 25.1, 25.4, 25.8, 30.4_

  - [~] 4.11 Implement `CallRecorder`
    - Mix Caller and Agent audio into one 8 kHz mono WAV and write it to S3 as a multipart upload that starts when the Recording_Notice has finished (or at the start when the Disclosure_Setting is off) and is not written during a Take_Over; retry the upload at call end on failure
    - _Requirements: 17.8, 18.4, 18.5_

  - [ ]* 4.12 Write contract and unit tests for the stream handler
    - Parse the recorded Exotel fixtures from spike C-1 (start event, media, mark, clear) and test the handler with a fake call session: hidden Caller number, 10-second media timeout, mismatched frames
    - _Requirements: 1.3, 3.5, 3.8_

- [ ] 5. Implement the Nova 2 Sonic client
  - [x] 5.1 Implement `SonicEvents`, `ToolSpecs`, `SystemPrompt`, and `HistoryBuilder`
    - Builders and parser for every input and output event of the design in the documented order; seven tool specs with JSON schemas; a system prompt without any protected data; history of at most 40 utterances plus one server-state line, passed through the PII_Guard in Stalling_Mode
    - Store the C-5 fixtures and parse them in a contract test
    - _Requirements: 7.4, 14.2, 14.8, 15.6, 20.9_

  - [ ]* 5.2 Write property test for Sonic event sequences
    - **Property 13: Sonic event sequence is well formed**
    - **Validates: Requirements 14.2, 14.8, 15.6, 12.12**

  - [x] 5.3 Implement voice selection for the Sonic session
    - Use `core.voice.VoiceSelector` with `kiara` and `arjun` as defaults and `tiffany` and `matthew` as selectable alternatives; keep the voice for the whole call and its replacement sessions; record a substitution in the Call_Log
    - _Requirements: 7.3, 7.5, 7.6_

  - [ ]* 5.4 Write property test for the constant voice
    - **Property 9: Voice is constant for the whole call**
    - **Validates: Requirements 7.3, 7.5, 7.6, 14.2**

  - [~] 5.5 Implement `SonicClient` and `SonicSession`
    - Wrap `InvokeModelWithBidirectionalStream`; send audio input; handle output events (transcript, speculative text, audio, tool use, usage, completion, `INTERRUPTED`); run each tool with a 2-second timeout and always send exactly one `toolResult`; server-to-model instructions through marked cross-modal text
    - Report Sonic start failures and stream errors as events to the caller
    - _Requirements: 4.4, 5.1, 5.4, 8.2, 15.6_

  - [~] 5.6 Implement `SessionRenewer`
    - Open a replacement session at 80 percent of `sonic.maxSessionSeconds`, build history from the stored transcript, switch at a quiet moment or after 3 seconds, replay the 1-second Caller ring buffer once, close the old session, and use the same procedure for the idle limit and unexpected stream end
    - _Requirements: 14.1, 14.2, 14.3, 14.4, 14.8_

  - [ ]* 5.7 Write property test for the renewal schedule
    - **Property 12: Session renewal schedule**
    - **Validates: Requirements 14.1, 14.2, 14.3, 14.4**

  - [~] 5.8 Wire the Audio_Bridge between Exotel and Sonic
    - Connect Exotel media to the resampler and `audioInput` within 100 ms, and `audioOutput` to the resampler and the pacer; do not forward Caller audio during the Recording_Notice; clear on `INTERRUPTED`; accumulate Caller and Agent audio durations
    - Test with fake Exotel and Sonic ports
    - _Requirements: 3.2, 3.3, 5.1, 5.5_

- [ ] 6. Implement the Agent_Tools on the `:core` rules
  - [x] 6.1 Implement `AgentTool`, argument validation, and the tool context
    - A common interface (`name`, schema, `execute(args, callState) -> JSON result`), JSON schema validation plus code validation, and a call state view holding the User_Id, the Frozen_Settings of that User, language, stalling mode, and counters
    - Tools are plain classes; they are registered in `ToolRegistry` in task 8.7
    - _Requirements: 8.2, 16.10_

  - [~] 6.2 Implement `get_gate_info`
    - `GateSelector.selectGate` with the server `Clock` in Asia/Kolkata at minute resolution, `GateInfoRenderer` text in English and Hindi, window times only on request, and both gates with `"clock": "UNAVAILABLE"` plus a Call_Log entry when the clock fails
    - _Requirements: 9.1, 9.2, 9.3, 9.4, 9.5, 9.6, 9.10, 9.11_

  - [ ]* 6.3 Write property test for gate selection
    - **Property 1: Gate selection partitions every instant**
    - **Validates: Requirements 9.1, 9.2, 9.3, 9.4, 9.10, 9.11, 9.12**

  - [ ]* 6.4 Write property test for the gate result text
    - **Property 2: `get_gate_info` result is exact and free of personal data**
    - **Validates: Requirements 9.5, 9.6, 7.7**

  - [~] 6.5 Implement `get_delivery_directions`
    - Return the Colony_Gate_Info first, then the Delivery_Steps in order with `{map_location}` replaced, the speak-in-Caller's-language note for single-language steps, the default language for other languages, the `OK` status for the first 4 calls and `REPETITION_LIMIT` afterwards, and a `MAP_LOCATION_MISSING` push request when no Map_Location is configured
    - _Requirements: 10.1, 10.2, 10.3, 10.4, 10.6, 10.11, 7.7, 7.8_

  - [ ]* 6.6 Write property test for directions order and completeness
    - **Property 4: Delivery directions order and completeness**
    - **Validates: Requirements 10.2, 7.7, 7.8**

  - [ ]* 6.7 Write property test for the repetition limit
    - **Property 5: Directions repetition limit**
    - **Validates: Requirements 10.3, 10.4**

  - [~] 6.8 Implement `ExotelClient` SMS and `send_map_sms`
    - Exotel SMS API with Basic auth and the DLT template variable; send at most once per call, never to an unknown or non-mobile number (record the reason), return `ALREADY_SENT` afterwards, and raise a `MAP_SMS_FAILED` alert on rejection or non-delivery while the call stays open
    - _Requirements: 10.5, 10.7, 10.8, 10.10_

  - [ ]* 6.9 Write property test for the single Map_SMS
    - **Property 6: Map SMS is sent at most once to an eligible number**
    - **Validates: Requirements 10.5, 10.7, 10.10**

  - [~] 6.10 Implement `log_note`
    - Accept intent values only from DELIVERY, DOOR_NOT_OPENED, SOLICITATION, and UNKNOWN; reject others with `REJECTED` and the current category; store accepted values in the call state within 2 seconds; support the other note kinds of the design
    - _Requirements: 8.2, 8.10_

  - [ ]* 6.11 Write property test for intent logging
    - **Property 7: Intent logging accepts only the four categories**
    - **Validates: Requirements 8.2, 8.10**

  - [x] 6.12 Implement the streaming PII_Guard in `:core`
    - `StreamingPiiGuard` that matches Protected_Terms across chunk boundaries, ignoring letter case and spacing or punctuation inside digit sequences, and reports the match no later than the completing chunk; use the 300 ms look-ahead buffer if spike C-5 requires it
    - _Requirements: 12.8, 12.9, 12.13_

  - [ ]* 6.13 Write property test for the PII_Guard
    - **Property 8: Streaming PII guard detects every protected term**
    - **Validates: Requirements 12.8, 12.9, 12.13**

  - [~] 6.14 Implement `get_stalling_persona` and the Stalling_Mode results
    - In Stalling_Mode, `get_gate_info` and `get_delivery_directions` return fictitious values from `FictitiousGate` and `FictitiousReplyLibrary` and never real gates, Night_Window times, Map_Location, or Protected_Terms
    - Build the persona text with `core.stalling.PromptBuilder`
    - _Requirements: 9.9, 12.6, 12.12, 20.9_

  - [ ]* 6.15 Write property test for Stalling_Mode results
    - **Property 3: Stalling mode never returns real gate or personal data**
    - **Validates: Requirements 9.9, 12.6, 12.12, 20.9**

- [~] 7. Checkpoint - Core and tools
  - Run `./gradlew :core:test :agent:test` and confirm that all property and unit tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [ ] 8. Implement the call policy, takeover, and fallbacks
  - [~] 8.1 Implement `CallPolicy` in `:core`
    - Pure `step(state, event, now) -> (state, effects)` with the phases, events, and effects of the design; counters for greeting repeat, directions repetitions, silence prompts, clarification prompts, and garbled repetitions; the 15-minute, 60-minute, and maximum-stalling limits; Gate_Query without clarification count
    - _Requirements: 6.5, 6.6, 12.3, 12.4, 12.5, 12.10, 13.2, 13.4, 13.5, 13.6, 13.7_

  - [ ]* 8.2 Write property test for the call policy
    - **Property 10: Call policy enforces limits and counters**
    - **Validates: Requirements 6.5, 6.6, 12.3, 12.4, 12.5, 12.10, 13.2, 13.4, 13.5, 13.6, 13.7, 22.6**

  - [~] 8.3 Implement `RouteDecider` in `:core`
    - Select the Agent only when the Agent is on, consent is accepted, the User is active, the User's month total is below the limit, fewer than 3 calls of the User are active, fewer than the Global_Call_Cap calls are active, and `From` is neither the User_Mobile of the resolved User nor any assigned Virtual_Number; otherwise select the Fallback_Route and record the cause (`USER_LIMIT`, `GLOBAL_CAP`, and the others)
    - _Requirements: 1.5, 1.7, 17.2, 17.4, 20.7, 22.5, 25.8, 29.1, 29.2, 29.3_

  - [ ]* 8.4 Write property test for the route decision
    - **Property 20: Route decision**
    - **Validates: Requirements 1.5, 1.7, 17.2, 17.4, 20.7, 22.5, 25.8, 29.1, 29.2, 29.3**

  - [~] 8.5 Add the takeover state machine and the `request_takeover` and `end_call` tools
    - Extend `CallPolicy` with the takeover events: one Bridge_Call at a time, ignore a second tap, no replacement Sonic_Session in TAKEOVER, "please wait" every 15 seconds, the 30-second nobody-available instruction while staying with the Agent, and the return to the Agent after a failed bridge with the failure recorded
    - Implement the two tools; `end_call` runs after the last `mark` has played
    - _Requirements: 11.1, 11.2, 11.3, 11.5, 11.6, 11.7, 11.10, 11.11_

  - [ ]* 8.6 Write property test for the takeover state machine
    - **Property 11: Take_Over state machine**
    - **Validates: Requirements 11.3, 11.4, 11.5, 11.6, 11.7, 11.10, 11.11**

  - [~] 8.7 Implement `CallSession`, `CallRegistry`, and `ToolRegistry`
    - One coroutine per call that handles events from one channel in order; run the policy effects (say, inject, clear, timers, alerts, close, renewal, fallback, record); register the seven tools; `CallRegistry` with a counter per User (3) and a global counter (cap 10) from configuration, `tryAcquire` and a release in the finalizer and the stream-closed handler with a 5-second watchdog; read the Frozen_Settings of the resolved User once at call start and store its Settings_Version in the Call_Log; greeting and Recording_Notice order with the Disclosure_Setting; transcript buffer with interrupted-utterance marking
    - Wire the `TenantResolver` and the `RouteDecider` into `/exotel/route` and the session factory into `/exotel/resolve`
    - _Requirements: 1.7, 5.3, 5.4, 6.1, 6.2, 6.3, 8.2, 8.7, 8.8, 20.5, 29.1, 29.2, 29.4, 29.5_

  - [~] 8.8 Implement the Fallback_Message and the failure paths
    - Copy the existing English and Hindi, female and male fallback audio (`app/src/main/assets/fallback`) into `:agent` resources resampled to 8 kHz
    - Play the Fallback_Message and apply the Fallback_Route (with the cause recorded) when Sonic fails to start, when a replacement session fails, when Time_To_First_Audio exceeds 3 seconds (greeting only), and when the server drains
    - Drop open calls found in DynamoDB at start to the Call_Log with a server-restart cause
    - _Requirements: 4.5, 15.1, 15.2, 15.3, 15.9, 14.5_

  - [~] 8.9 Implement the takeover integration with Exotel
    - Speak "connecting you", close the stream, answer `/exotel/next` with the User_Mobile and the 15-second dial timeout, handle the `resume=1` return with a new Sonic_Session and history, and record the Take_Over start time, duration, and failure
    - Use the Legs and Bridge APIs instead if spike C-3 chose alternative B
    - _Requirements: 11.4, 11.5, 11.7, 11.9_

  - [ ]* 8.10 Write unit tests for the call flows
    - Greeting and notice order with the Disclosure_Setting on and off, `end_call` after the last `mark`, silence flows, a failed bridge returning to the Agent, and the fallback causes, all with fake ports
    - _Requirements: 6.1, 6.2, 6.3, 11.7, 15.9_

- [ ] 9. Implement persistence, recordings, traces, and cost
  - [~] 9.1 Implement the data access layer for DynamoDB
    - Define an `ItemStore` port (get, put with condition, update, delete, query by key condition, transact) with a DynamoDB implementation and an in-memory fake that has the same conditional-write and key-condition semantics
    - `UserScopedRepository(userId)` for the items `PROFILE`, `CONSENT`, `DEVICE#...`, `CALL#...`, `TRACE#...`, `COST#...`, `FWDTEST`, and `OPEN#...` with `CoreJson`, every key built through `Keys`; no method accepts a raw partition key; time-ordered `Query` for the call list; transcript over 300 KB to S3 under `users/<sub>/`; 3 retries at 1-second intervals, then a `NOT_LOGGED` alert and a redacted log line
    - `DirectoryRepository` for the number lookup, user listing (`gsi1`), and open-call markers; no access to call content
    - A build rule (test) that fails when code outside the `store` package uses the DynamoDB or S3 clients
    - _Requirements: 18.1, 18.9, 20.3, 20.8, 28.1, 28.2, 28.5_

  - [ ]* 9.2 Write property test for the Call_Log and settings round trip
    - **Property 17: Call_Log and settings round trip**
    - **Validates: Requirements 18.1, 18.9, 20.3, 20.4, 20.8**

  - [~] 9.3 Implement `RecordingStore`
    - S3 upload and delete under `users/<sub>/recordings/`, taking a `UserId` and refusing to presign a key outside that prefix; presigned URL that expires in 10 minutes; and the recording status values (`AVAILABLE`, `NOT_FOUND`)
    - _Requirements: 18.4, 18.7, 17.7, 28.4_

  - [~] 9.4 Implement `CostEstimator` and the monthly counter in `:core` and `:agent`
    - Estimate from the Exotel minutes and the Sonic input and output minutes at the configured rates; atomic month total per User in DynamoDB (`USER#<sub>` / `COST#<yyyy-MM>`); the 80 percent notification exactly once and the 100 percent switch to the Fallback_Route, per User; no billing or invoice
    - _Requirements: 22.1, 22.4, 22.5, 22.8_

  - [ ]* 9.5 Write property test for cost and the monthly limit
    - **Property 19: Cost estimate and monthly limit**
    - **Validates: Requirements 22.1, 22.4, 22.5, 1.5**

  - [~] 9.6 Implement `RetentionPolicy` and `RetentionSweeper`
    - A pure selection of items whose start time plus retention is not after now; a sweeper that runs at start and every 6 hours, lists active Users from the `DirectoryRepository`, and for each User deletes that User's call items, trace items, and S3 objects under `users/<sub>/` with that User's retention, and retries on failure; immediate deletion of one call on request
    - _Requirements: 17.5, 17.6, 17.7, 28.7_

  - [ ]* 9.7 Write property test for retention
    - **Property 18: Retention selection is exact and idempotent**
    - **Validates: Requirements 17.5, 17.6, 17.7**

  - [~] 9.8 Implement `CallTrace`, `Metrics`, and `StructuredLog`
    - Trace events with the listed event names, CloudWatch embedded metric lines (`CallCount`, `TimeToFirstAudioMs`, `ReplyLatencyMs`, `FallbackCount`, `SonicErrorCount`, `ActiveCalls`), and JSON logs with no Caller number, transcript, or Protected_Term; 30-day trace TTL
    - _Requirements: 16.8, 23.2, 23.3, 23.7_

  - [ ]* 9.9 Write property test for traces without personal content
    - **Property 24: Traces and log lines contain no personal content**
    - **Validates: Requirements 16.8, 23.3**

  - [~] 9.10 Implement `CallFinalizer`
    - Within 5 seconds of the call end, compute the Latency_Report (time to first audio, median and maximum reply latency), the Cost_Estimate, the outcome, the post-call check of the gate numbers spoken against the tool result, the recording key, and the Take_Over details; store the entry and trace and send the end event
    - _Requirements: 1.4, 4.6, 11.9, 18.3, 22.1_

  - [ ]* 9.11 Write an integration test for the repository
    - DynamoDB round trip of a call entry and the sweeper against a test table (1 to 3 examples)
    - _Requirements: 18.9, 17.5_

  - [~] 9.12 Implement `SettingsStore` with optimistic concurrency
    - `update(userId, expectedVersion, candidate)`: validate with `SettingsValidator` and the cross-record rule (`userMobile` is not an assigned number), then a conditional `PutItem` (`attribute_not_exists` for expected version 0, `version = :expected` otherwise) that writes `version + 1`; on a failed condition read the stored item and return `Conflict(document, version)`; return `Invalid(field)` on validation failure
    - `get(userId)` returns defaults with version 0 when no item exists; `freeze(userId)` returns the consistent snapshot used at call start
    - _Requirements: 20.2, 20.4, 20.5, 27.1, 27.2, 27.3, 27.4, 27.5, 27.6, 27.7_

  - [ ]* 9.13 Write property test for settings version conflict handling
    - **Property 26: Settings version conflict handling**
    - Run against the in-memory `ItemStore` fake, including interleaved concurrent attempts with the same expected version
    - **Validates: Requirements 27.3, 27.4, 27.5, 27.6, 27.12**

  - [~] 9.14 Implement `TenantResolver`, `NumberAssignmentService`, and the unassigned-call log
    - `TenantResolver.resolve(to)`: normalize with `PhoneNumbers.normalize`, `GetItem NUMBER#<e164>`, check the profile is active, 30-second cache for hits and misses, cache cleared on Admin writes; returns the User_Id or `OutOfService(reason)`
    - Forwarded_From_Hint handling: compare with the User_Mobile and expose only `hintPresent` and `hintMatchesUserMobile`; never used for the lookup
    - `NumberAssignmentService`: assign and unassign with `TransactWriteItems` and `attribute_not_exists` conditions (409 when the number or the User already has an assignment); reject a number that equals any User's `userMobile`; deactivate a User
    - `UnassignedCallLog` writes `OPS#UNASSIGNED#<date>` items with the `To` number and the reason, without the Caller number
    - _Requirements: 25.1, 25.2, 25.3, 25.4, 25.5, 25.6, 25.7, 26.2, 26.3, 26.4, 26.5, 26.9_

  - [ ]* 9.15 Write property test for tenant resolution
    - **Property 27: Tenant resolution**
    - Cover each assigned number resolving to exactly one User, unassigned and invalid numbers rejected, textual variants of one number, deactivated Users, and a hint that never changes the result
    - **Validates: Requirements 25.1, 25.2, 25.3, 25.4, 25.6, 25.10, 25.11**

  - [ ]* 9.16 Write property test for data isolation of the query layer
    - **Property 28: Data isolation of the query layer**
    - Generate Users with long shared `sub` prefixes and call identifiers that contain `#`; run list, get, delete, sweep, and presign for one User and assert that only that User's records are returned or changed
    - **Validates: Requirements 28.1, 28.2, 28.3, 28.4, 28.5, 28.7, 28.9, 30.2**

  - [~] 9.17 Implement the admin command-line script
    - `AdminCli` main class in `:agent` and `scripts/admin/callagent-admin` that run assign, unassign, list, and deactivate through `NumberAssignmentService` with `--profile ldots --region ap-northeast-1`; add a `bootstrap-user <sub>` command that creates the profile of a User before first sign-in
    - _Requirements: 26.2, 26.4, 26.5, 26.6_

- [ ] 10. Implement the Companion_API, push notifications, and the SMS fallback
  - [~] 10.1 Implement `JwtVerifier`, `RateLimiter`, and the route scaffold
    - Verify the Cognito token against the JWKS of the existing ap-south-1 pool (cache 1 hour; check `iss`, audience or client id, `exp`, and `token_use`), return the `sub` and the `cognito:groups` claim, 401 for an invalid token, and 429 above 60 requests per minute per `sub`
    - Make the verified `sub` the only source of the User_Id for every handler (never the path, query, or body) and answer 403 for a deactivated User
    - Serve the Companion_API on `api.leadingdots.com` with the base path `/api/v1`
    - _Requirements: 16.6, 16.7, 16.9, 28.2, 26.5_

  - [~] 10.2 Implement the settings, consent, and device routes
    - `GET /me` (creates the profile on first use and returns the assigned number or null), `GET /settings` (`{version, settings}`), and `PUT /settings` with `expectedVersion` through `SettingsStore` (200, 400 with the field name, 409 with the stored document; answer within 3 seconds)
    - `POST` and `DELETE /consent`, and `PUT` and `DELETE /devices/{deviceId}` (up to 5 devices per User, oldest replaced)
    - _Requirements: 17.1, 17.3, 20.3, 20.4, 20.8, 26.1, 26.8, 27.2, 27.3, 27.4, 27.6, 30.1_

  - [~] 10.3 Implement the call log routes
    - `GET /calls` (newest first, cursor), `GET` and `DELETE /calls/{id}`, `GET /calls/{id}/recording`, `POST /calls/{id}/report-status`, and `GET /usage/month`, all through `UserScopedRepository` of the token's `sub`; a call identifier of another User answers 404
    - _Requirements: 18.6, 18.7, 17.7, 22.4, 28.2, 28.3_

  - [~] 10.4 Implement the `/live` WebSocket
    - First-message token authentication, the events of the design (`call_started`, `transcript`, `intent`, `language`, `state`, `takeover_status`, `call_ended`), a ping every 20 seconds, and one connection counted for the rate limit
    - _Requirements: 19.1, 19.9_

  - [~] 10.5 Implement the live call actions
    - `POST /calls/{id}/takeover`, `/end`, `/quick-reply`, and `/alert-ack`; ignore a second takeover tap; reject actions on an ended call
    - _Requirements: 11.4, 11.10, 19.9_

  - [~] 10.6 Implement the forwarding test endpoints
    - `POST` and `GET /forwarding/test`; arm for 10 minutes, record the first call after arming as the test call, and report received or not received
    - _Requirements: 2.6, 2.7_

  - [ ]* 10.7 Write property test for the test call window
    - **Property 23: Test call window**
    - **Validates: Requirements 2.6, 2.7**

  - [~] 10.8 Implement `FcmClient` and `AlertService`
    - FCM HTTP v1 with the service account from Secrets Manager and high-priority data messages for the types of the design
    - Send each notification to every stored device of the User who owns the call and to no other device; remove a device token that FCM reports as unregistered
    - Takeover_Alert acknowledgment wait of 5 seconds; on a missing token, an FCM rejection, or no acknowledgment, send an SMS with the Caller number to the User_Mobile of that User through Exotel and write the failure to the trace
    - _Requirements: 11.2, 11.8, 19.8, 30.2, 30.3_

  - [~] 10.9 Wire the notification types into the call flow
    - Send `CALL_STARTED`, `TAKEOVER_ALERT` within 3 seconds, `CALL_ENDED`, `UNKNOWN_CALLER_SUMMARY`, `SILENT_CALLER`, `MAP_SMS_FAILED`, `MAP_LOCATION_MISSING`, `PII_EVENT`, `NOT_LOGGED`, `COST_80`, and `COST_100` from the session and the finalizer
    - _Requirements: 10.6, 10.8, 11.2, 12.9, 22.4_

  - [ ]* 10.10 Write unit tests for the API and the alerts
    - Expired and wrong-audience tokens, the 429 response, and the alert fallback to SMS when no acknowledgment arrives
    - _Requirements: 16.6, 16.7, 11.8_

  - [~] 10.11 Implement the Admin_API routes
    - `GET /admin/users`, `GET /admin/numbers`, `PUT /admin/numbers/{e164}` (body `{userId}`), `DELETE /admin/numbers/{e164}`, and `POST /admin/users/{userId}/deactivate` through `NumberAssignmentService`; accept only tokens whose `cognito:groups` contains `admins` (403 otherwise); 409 on a duplicate assignment
    - _Requirements: 26.2, 26.3, 26.4, 26.5, 26.6, 26.7_

  - [ ]* 10.12 Write API tests for settings, admin, and isolation
    - `PUT /settings` returns 200, 400 with the field name, and 409 with the stored document; the Admin_API returns 403 without the group and 409 on a duplicate; a foreign call identifier returns 404 on every call route and on takeover, end, quick-reply, and recording; a Take_Over request accepts no destination number; `/live` delivers only the events of the authenticated User
    - _Requirements: 26.3, 26.7, 27.3, 27.4, 27.6, 28.3, 28.6, 30.5, 30.6_

- [~] 11. Checkpoint - Server complete
  - Run `./gradlew :core:test :agent:test` and confirm that the server code and all property tests pass locally
  - Ensure all tests pass, ask the user if questions arise.

- [ ] 12. Update the Companion_App
  - [x] 12.1 Archive the on-device audio path
    - Run a dependency scan of what `ui/` still uses from `a11y/`, `audio/`, `asr/`, `tts/`, `translate/`, `cloudtts/`, `nlg/`, `telecom/`, `recording/`, `service/`, and `sms/`
    - Move those packages to a Gradle module `:archive-ondevice` that `:app` does not depend on (a note in `README.md` records that the on-device path is archived and not shipped; a git tag `archive/ondevice-audio` marks the last commit that contained it in `:app`)
    - Adapt the `ui/` screens that referenced the on-device pipeline
    - _Requirements: 24.3, 24.4_

  - [~] 12.2 Clean the manifest and keep the 16 KB page-size compatibility
    - Remove `RECORD_AUDIO`, `ANSWER_PHONE_CALLS`, `SEND_SMS`, `SYSTEM_ALERT_WINDOW`, the microphone foreground-service entries, the accessibility service, and the call screening service from the manifest
    - Remove the `sherpa-onnx` AAR and any native libraries that only the archived code used; keep `:app` free of 4 KB-aligned native libraries and add the 16 KB check script `scripts/check-16kb.sh` (zipalign and ELF `LOAD` segment alignment of the release APK)
    - _Requirements: 24.3, 24.4_

  - [~] 12.3 Add the API client, authentication, and the data layer changes
    - HTTP and WebSocket client for `api.leadingdots.com` with the Cognito ID token (token in the first WebSocket message), Room cache of the last server settings document with its Settings_Version and of the call log, and the existing outbox extended to store the base version and the changed fields of a pending settings edit
    - Declare all navigation destinations in one shared `Routes.kt` so that screen tasks do not edit the same file
    - _Requirements: 20.6, 16.6_

  - [~] 12.4 Add the Privacy_Consent screen
    - Show the consent text (processing outside India in the Agent_Region, recording, and retention) after first sign-in; keep the Agent off and the forwarding codes hidden until accepted; support withdrawal
    - _Requirements: 17.1, 17.2, 17.3_

  - [~] 12.5 Add `ForwardingCodes` and the forwarding setup, turn-off, and test call screens
    - Add `cloud.ForwardingCodes` to `:core`: the set, clear, ring-time, and unconditional codes for a validated number, with `#` encoded as `%23` in the dialer URI
    - Use the Virtual_Number assigned to the User (from `GET /me`) as `<number>` in every code, and show the setup only when a number is assigned
    - Build the setup screen (`**61*<number>**20#`, `**67*<number>#`, `**62*<number>#`, each with a button that opens the dialer with the code prepared and not dialed), the unconditional option with a warning, the turn-off screen (`##61#`, `##67#`, `##62#`, `##002#`), the carrier charge and Wi-Fi calling text (updated from spike C-4), and "Send test call" with a 10-minute countdown and a result
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 2.6, 2.7, 2.8_

  - [ ]* 12.6 Write property test for the forwarding codes
    - **Property 22: Forwarding codes**
    - **Validates: Requirements 2.1, 2.3, 2.4, 2.5**

  - [~] 12.7 Add push handling and the Takeover_Alert
    - Firebase Messaging service for the data message types of the design, the notification channels (high importance for `TAKEOVER_ALERT` with sound), the "Take over" action that calls `POST /calls/{id}/takeover` from a short-lived worker, and the `alert-ack` call within 5 seconds
    - Add `google-services.json` from task 0.3 to the app project and to `.gitignore`
    - _Requirements: 11.2, 11.4, 19.8_

  - [~] 12.8 Add the live call screen
    - Full-screen screen with the Caller number, intent, language, elapsed time, live transcript from `/live`, "Take over", "End call", Quick_Replies, and bridge status; show an ended call as ended and disable Take over
    - _Requirements: 19.1, 19.9, 11.4_

  - [~] 12.9 Add the settings sync
    - Settings fields of Requirement 20 (including the User_Mobile for Take_Over, voice choice `kiara` or `arjun` with `tiffany` and `matthew` as alternatives, retention, Disclosure_Setting warning, cost rates, and monthly limit), validated with `SettingsValidator` and saved with `PUT /settings` and `expectedVersion`
    - Offline and conflict handling: keep the cache and the pending edit, send on reconnect when the versions match, otherwise run `SettingsMerge.threeWay`, show a per-field choice for fields changed on both sides, and retry with the stored version (at most 3 attempts); replace the cache with every document received from the server
    - _Requirements: 6.4, 7.6, 20.1, 20.3, 20.4, 20.6, 27.8, 27.9, 27.10, 27.11_

  - [~] 12.10 Add the call detail and cost screens
    - Latency report, trace events, transcript, cost labeled as an estimate, month total, and recording playback through the presigned URL; show a finished call in the list within 10 seconds
    - _Requirements: 18.7, 19.10, 22.4, 22.7, 23.2_

  - [~] 12.11 Keep the spam report flow
    - Verify that `core.report.SpamReportBuilder` and the report status flow still work against the new call log (report status through `POST /calls/{id}/report-status`)
    - _Requirements: 21.10_

  - [ ]* 12.12 Write property test for the spam report message
    - **Property 25: Spam report message round trip**
    - **Validates: Requirements 21.10**

  - [ ]* 12.13 Write Compose UI tests
    - Forwarding setup (codes shown, dialer opened with the code prepared and not dialed), live call screen (Take over action, ended-call state), and the consent gate
    - _Requirements: 2.1, 19.9, 17.2_

  - [~] 12.14 Verify the release build
    - Add a test that fails when the release manifest contains `RECORD_AUDIO`, a call screening service, or an accessibility service; run `:core` tests in the release build; run `scripts/check-16kb.sh` on the release APK
    - _Requirements: 24.4, 24.7_

  - [~] 12.15 Add the onboarding flow
    - Guided steps after the first sign-in: Privacy_Consent, "waiting for your number" screen that polls `GET /me` at most every 60 seconds while open and then shows the assigned number, User_Mobile entry with validation, forwarding setup (12.5), and settings; keep the Agent off until consent, number, and User_Mobile exist
    - When the server holds no settings document, offer to upload the cached local settings with `expectedVersion` 0
    - _Requirements: 31.1, 31.2, 31.3, 31.4, 31.5, 31.6, 26.8_

  - [ ]* 12.16 Write unit tests for the settings merge and the sync
    - `SettingsMerge.threeWay` for unchanged fields, fields changed on one side only, and fields changed on both sides; the 409 retry bound; the cache replaced by the server document
    - _Requirements: 27.9, 27.10, 27.11_

- [~] 13. Checkpoint - App complete
  - Run `./gradlew :core:test :agent:test :app:testReleaseUnitTest` and `scripts/check-16kb.sh`
  - Ensure all tests pass, ask the user if questions arise.

- [ ] 14. Deploy to ap-northeast-1 and validate end to end
  - [~] 14.1 Deploy the certificate, DNS records, and network
    - Deploy the template parameters for the certificate and network with `sam deploy --profile ldots --region ap-northeast-1`
    - With a Route 53 zone (task 0.4) the validation and ALIAS records are created by the template; otherwise create the validation CNAMEs and the CNAMEs for `agent.leadingdots.com` and `api.leadingdots.com` at the registrar and wait for the certificate to be issued
    - _Requirements: 16.1, 4.7_

  - [~] 14.2 Push the image and deploy the full stack
    - Run `scripts/build-push-agent.sh`, then `sam deploy --profile ldots --region ap-northeast-1` with `ImageUri`, `CognitoUserPoolId`, and `CognitoClientId`
    - Confirm `/health` through `https://agent.leadingdots.com` and that a deployment starts the new task before the old one stops
    - _Requirements: 4.7, 1.4_

  - [~] 14.3 **USER** Set the secrets and the Exotel flow
    - Put the Exotel credentials, the stream Basic credentials, the token HMAC secret, and the FCM service account key into Secrets Manager (`aws secretsmanager put-secret-value --profile ldots --region ap-northeast-1`)
    - In the Exotel dashboard create the template Number_Flow of the design (Passthru route with the Out_Of_Service branch, Voicebot with the resolve URL at `sample-rate` 8000, Connect with the `/exotel/next` URL, and the Fallback_Route) and set the dial timeout to 15 seconds; link the flow to every assigned ExoPhone
    - Create the Number_Flow of each assigned ExoPhone from this template with the owner's User_Mobile as the static fallback number, following `docs/admin-number-assignment-checklist.md` (task 14.8); the server-down behavior is decided: the call is passed to the User (requirements open question 16)
    - _Requirements: 16.2, 16.5, 1.5, 15.9, 25.4_

  - [~] 14.4 Add the end-to-end verification script
    - Add `scripts/e2e/verify_calls.sh` that reads the last calls through the Companion_API and asserts Time_To_First_Audio, Reply_Latency p90, the trace events, the recording status, and the cost for each scripted scenario
    - Add `docs/cloud-e2e-checklist.md` with the scenarios: delivery in Hindi, English, and Hinglish; delivery during the Night_Window; door not opened with a takeover, a 30-second timeout, and a failed dial; solicitation until the stalling limit; unknown caller; silence; hidden Caller number; Agent off; call beyond 3 concurrent for one User; the global cap; a call longer than 8 minutes; a server restart during a call; the monthly limit
    - Add the multi-user scenarios: two Users on two ExoPhones with different settings (each call uses its own User's gates and voice), a call to an unassigned ExoPhone (Out_Of_Service_Message and no Call_Log entry), a settings update with a stale version (409), and a read of the other User's call identifier through the Companion_API (404)
    - _Requirements: 4.1, 4.2, 4.3, 4.6, 18.7, 25.4, 27.4, 28.3, 29.1, 29.2_

  - [~] 14.5 **USER** Run the end-to-end scenarios with real calls
    - Set the forwarding codes on the User_Mobile, call from a second phone through each scenario of `docs/cloud-e2e-checklist.md`, and run `scripts/e2e/verify_calls.sh`
    - Report the failures to the coding agent
    - _Requirements: 1.1, 4.1, 4.2, 4.3, 11.4, 14.1_

  - [~] 14.6 **USER** Validate the Companion_App on the phone
    - Install the release build, accept the consent, check the live alert and Take over, the live transcript, the call log, the settings sync, the spam report, and the turn-off codes
    - _Requirements: 19.1, 19.9, 20.3, 21.10_

  - [~] 14.7 **USER** Create the first two Users and assign their numbers
    - Sign in to the app with the owner's account and with a test account, then run `scripts/admin/callagent-admin assign` for each account and its ExoPhone (the Admin_API with an `admins` token is an alternative)
    - Complete the checklist of `docs/admin-number-assignment-checklist.md` for each number, including the test call with the Agent server stopped that rings the User_Mobile
    - Confirm that each app shows its own number and that the owner's cached local settings were uploaded as version 1
    - _Requirements: 26.2, 26.8, 31.2, 31.3, 31.5_

  - [~] 14.8 Document the admin assignment checklist and the static fallback Number_Flow
    - Write `docs/admin-number-assignment-checklist.md` with the steps of the design section "Admin and number assignment": assign the number, clone the template Number_Flow in the Exotel dashboard, set the Fallback branch to a Connect applet with the User_Mobile as a static E.164 number and a 15-second dial timeout, add the Voicemail applet on no answer, link the flow to the ExoPhone, test with the Agent server stopped, and record completion; add the steps for a changed User_Mobile and for a removed assignment
    - Make `scripts/admin/callagent-admin assign` print the checklist reminder and store `flowConfigured`, `flowFallbackMobile`, and `flowConfiguredAt` on the Number_Assignment through a `confirm-flow` subcommand, and make `list` show assignments whose flow is not confirmed
    - Note in the document that the setup is manual until Exotel exposes a flow API
    - _Requirements: 15.10, 15.11, 15.12, 26.10, 26.11_

- [~] 15. Final checkpoint - Ensure all tests pass
  - Run all tests, confirm that every USER task in tasks 0 and 14 is complete, and confirm that the spike results in design.md are current
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional and can be skipped for a faster MVP. The 25 property tests are optional sub-tasks placed next to the code they cover.
- Tasks marked **USER** are done by the User. They are not coding tasks and are listed because they gate the spikes, the deployment, and the real-call validation.
- Each task references specific requirements for traceability.
- Checkpoints ensure incremental validation; the first checkpoint (task 2) is a go/no-go gate for all production work.
- Property tests validate universal correctness properties; unit, contract, and integration tests validate examples, recorded fixtures, and AWS behavior.
- Multi-user tasks added in this revision: 0.6, 1.8, 3.7, 9.12 to 9.17, 10.11, 10.12, 12.15, 12.16, and 14.7, plus changes to 0.1, 1.5, 3.2, 3.4, 4.8, 4.10, 6.1, 8.3, 8.7, 9.1, 9.3, 9.4, 9.6, 10.1 to 10.3, 10.8, 12.3, 12.5, 12.9, 14.3, and 14.4. Tasks 0.5 and 1.1 were already complete and are unchanged.
- The on-device audio path is archived in `:archive-ondevice` and not shipped.

## Task Dependency Graph

Waves are ordered by prerequisites, not by checkpoint. Phase A (waves 0 to 3) holds only spike-independent code and its tests, so it can run while the User waits on Exotel, DLT, and Firebase. Phase B (waves 4 to 17) holds every USER task, the spikes, and everything gated by them. Completed tasks (0.4 to 0.7, 1.1, 1.4, 3.1, 3.2, 3.7, 12.1) are omitted. Checkpoints 2, 7, 11, 13, and 15 are not in the graph.

Depends on (gated tasks):
- 0.1, 0.2: gate the Exotel spikes 1.2, 1.5 and deployment 14.1.
- 0.3 (Firebase): gates 10.8 and 12.7 (and through 10.8 also 10.9, 10.10) and 14.3.
- 1.5 (C-1 stream format): gates 4.10, 4.11, 4.12, 5.8, and through them 8.7, 8.8, 8.9, 10.4, 10.5, 10.6.
- 1.2 (C-6 SMS): gates 6.8, 6.9, and through 6.8 also 8.7 and 10.9.
- 1.7 (C-3 takeover): gates 8.9 and through it 8.10.
- Checkpoint 2 (after 1.2, 1.3, 1.5, 1.6, 1.7, 1.8) must pass before 14.1; checkpoint 11 (after all of tasks 3 to 10) before all of task 14; checkpoint 13 (after task 12) before 14.6.
- 3.3 uses manual Cloudflare DNS records per `docs/cloud/dns.md`.
- 3.3 and 3.4 write the same template, so they are in different waves.
- 12.14 does not depend on 12.7 and stays in Phase A.

```json
{
  "waves": [
    { "id": 0, "tasks": ["3.3", "3.5", "4.1", "4.3", "4.7", "4.8", "5.1", "5.3", "6.1", "6.12", "8.1", "8.3", "9.1", "9.8", "10.1", "12.2"] },
    { "id": 1, "tasks": ["3.4", "4.2", "4.4", "4.5", "4.9", "5.2", "5.4", "5.5", "6.2", "6.5", "6.10", "6.13", "8.2", "8.4", "8.5", "9.2", "9.3", "9.4", "9.9", "9.12", "9.14", "12.3"] },
    { "id": 2, "tasks": ["3.6", "4.6", "5.6", "6.3", "6.4", "6.6", "6.7", "6.11", "6.14", "8.6", "9.5", "9.6", "9.10", "9.13", "9.15", "9.17", "10.2", "10.3", "10.11", "12.4", "12.5", "12.8", "12.9", "12.10", "12.11"] },
    { "id": 3, "tasks": ["5.7", "6.15", "9.7", "9.11", "9.16", "12.6", "12.12", "12.13", "12.14", "12.15", "12.16"] },
    { "id": 4, "tasks": ["0.1", "0.2", "0.3", "1.3"] },
    { "id": 5, "tasks": ["1.2", "1.5", "10.8", "12.7"] },
    { "id": 6, "tasks": ["1.6", "1.7", "1.8", "4.10", "4.11", "6.8", "10.10"] },
    { "id": 7, "tasks": ["4.12", "5.8", "6.9", "10.6"] },
    { "id": 8, "tasks": ["8.7", "10.7"] },
    { "id": 9, "tasks": ["8.8", "10.4", "10.9"] },
    { "id": 10, "tasks": ["8.9", "10.5"] },
    { "id": 11, "tasks": ["8.10", "10.12"] },
    { "id": 12, "tasks": ["14.1", "14.4", "14.8"] },
    { "id": 13, "tasks": ["14.2"] },
    { "id": 14, "tasks": ["14.3"] },
    { "id": 15, "tasks": ["14.7"] },
    { "id": 16, "tasks": ["14.5"] },
    { "id": 17, "tasks": ["14.6"] }
  ]
}
```
