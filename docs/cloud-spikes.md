# Cloud call agent spikes

Raw logs go to `spike-cloud/results/` (git-ignored); frame fixtures to `spike-cloud/results/frames/`.
Fill each table when its spike runs, with a go / no-go decision per the design table.

## C-6 Exotel SMS and DLT template (task 1.2)
| Date | Measurement | Result | Notes |
|---|---|---|---|
| | DLT entity, sender ID, template approval (Map_SMS link, Takeover alert) | | |
| | Template SMS delivery time to Indian mobile (target under 30 s) | | |
| | **Decision** | | |

## C-4 Airtel forwarding behavior (task 1.3)
| Date | Scenario (unanswered / busy / airplane; data / Wi-Fi calling) | Forwards? | Ring time honored? | Exotel `From` = Caller? |
|---|---|---|---|---|
| | | | | |
| | **Decision** | | | |

## C-5 Nova 2 Sonic session behavior, ap-northeast-1 (task 1.4)
Driver: `./gradlew :spike-cloud:c5 -Pscenario=basic|interrupt|inject|history|long` (`SonicSession.kt`, `SonicSpike.kt`). Raw events: `spike-cloud/results/c5/<scenario>-<epoch>/*.events.jsonl` and `results/c5.jsonl` (git-ignored). Representative fixtures for task 5.1: `spike-cloud/src/test/resources/fixtures/sonic/` (see its README). Input audio: `en_01..en_04`, `hi_01` WAVs at 16 kHz in real time (32 ms frames), plus silence frames. Test machine is in India (TCP connect to Tokyo 150 ms, TLS done at 300 ms), so latencies below include about 150 ms of round trip that a Fargate task in Tokyo will not have. Total streamed session time was about 25 minutes (including one 8-minute run and 3 discarded or repeated runs).

| Date | Measurement | Result | Notes |
|---|---|---|---|
| 2026-11-22 | Session length / 8-minute limit | **Confirmed.** Stream cut at 480.9 s after open (Caller-audio offset 480000 ms) while a Caller clip was being sent. | The cut is abrupt: SDK error `ModelTimeoutException`, HTTP 408, "Model has timed out in processing the request". No warning event, no `completionEnd`, no `sessionEnd` came first. Silence frames were sent the whole time, so the limit is on connection age, not on activity. Renewal at 80 percent (6 min 24 s) is required and the server must also treat this error as a normal renewal trigger. |
| 2026-11-22 | Replacement with history | **Works.** A second session opened while the first was still streaming (two concurrent sessions accepted). First server event 0.24 and 0.28 s after open. History of `USER` + `ASSISTANT` text (built from the first session's transcript) was accepted after the system prompt and before audio; asked to recall, the agent said "I will inform Ankur about the delivery." | Only 2 history messages were tested, not 40. History must be built after the `FINAL` assistant text has arrived (see ordering row), otherwise the last `ASSISTANT` turn is missing. Requires the SDK input type `InvokeModelWithBidirectionalStreamInput.chunkBuilder().bytes(...)`. |
| 2026-11-22 | Tool call round trip | `toolUse` arrives about 0.46 s after `userSpeechEnd`, and `contentEnd` (`stopReason` `TOOL_USE`) follows within 2 ms. After the `toolResult` is sent (immediately, 2 to 3 ms after that `contentEnd`), the first audio arrives **0.60 to 0.86 s** later (5 round trips: 597, 605, 676, 786, 863 ms). | `get_gate_info` and `get_delivery_directions` results were read word for word in all 2 English turns (`speak_exactly` text matched exactly); the Hindi turn was a Hindi rendering, as expected. The model called `log_note` INTENT with a sensible value for the delivery, Hindi delivery, and solicitation clips (3 of 3), and chose a gate or directions tool for the two address questions. The model sometimes says nothing after a tool call unless the system prompt tells it to speak. |
| 2026-11-22 | `INTERRUPTED` signal delay | **Not met on this link: 427, 329, 329 ms** from the first speech frame sent to `INTERRUPTED` received (3 trials, Caller clip over a long Agent sentence). | `userSpeechStart` arrives 2 to 3 ms before the `INTERRUPTED` event, so the delay is the model's speech detection (about 180 to 280 ms after removing the 150 ms round trip), not the event path. `INTERRUPTED` is the `stopReason` of the **TEXT ASSISTANT FINAL block** that contains the text `{ "interrupted" : true }`, not of the audio block; no `audioOutput` arrived after it (0 of 3 trials). Sonic sends audio in bursts faster than real time, so the server must flush its own Outbound queue on this signal. Re-measure from the Tokyo task in task 14. |
| 2026-11-22 | Speculative text before audio ordering | **Confirmed in 100 percent of turns** (greeting 2 of 2, 5 of 5 replies, all 10 injected sentences). The whole sentence arrives as one `SPECULATIVE` `textOutput` **about 95 to 110 ms before the first `audioOutput`** of that sentence (for example 1743 ms vs 1847 ms). | `FINAL` assistant text arrives late: when the modelled playback of the audio ends (about 4 to 7 s after the audio), or at once when the Caller barges in (then preceded by the `{ "interrupted" : true }` block). `USER` `FINAL` text arrives in chunks (`PARTIAL_TURN`) at about the time of `userSpeechEnd`. No 300 ms look-ahead buffer is needed, but the PII_Guard must hold each sentence's audio until the guard has passed its `SPECULATIVE` text. |
| 2026-11-22 | Injected text instructions (10 trials) | **10 of 10.** The spoken `FINAL` text equalled the quoted sentence word for word in every trial (overlap 1.0), the marker and the instruction were never read aloud, the Hindi sentence came out in Devanagari. First audio 0.54 to 1.53 s after the text was sent (median about 0.6 s). | Message form: `[[SERVER]] Say exactly: "<sentence>"` as `USER` text with `interactive` true, with a system prompt rule that such messages come from the system. Sentences: please wait, silence prompt, goodbye, corrective, Quick_Reply, nobody available, repeat request, message noted, Hindi please wait, connecting. All were sent while the Caller was silent; injection during Caller speech was not tested. A first scoring run showed 1 of 10 because the harness read `FINAL` text before it was emitted (harness bug, fixed; raw events of the first run are in `results/c5/inject-1795374312`). |
| 2026-11-22 | Cross-modal greeting trigger | **Works.** A `USER` text with `interactive` true sent right after the audio `contentStart` made the Agent speak the given greeting exactly; first audio 1.33 and 1.43 s after the text was sent (1.8 s after session start). | No Caller audio was needed first. |
| 2026-11-22 | Idle timeout | **55 seconds without audio bytes or interactive content.** Idle session (audio `contentStart` sent, then nothing) was closed at 56.3 s with `ValidationException`, HTTP 400, "Timed out waiting for audio bytes or interactive content. Please ensure gaps between audio bytes and interactive content are less than 55 seconds." | Sending silence frames every 32 ms keeps a session alive to the 8-minute limit (long run). The server must keep sending silence frames during the Take_Over wait and while the Recording_Notice suppresses Caller audio. |
| 2026-11-22 | SDK version / model or inference profile id | AWS SDK for Java v2 `bedrockruntime` **2.31.50**, model id **`amazon.nova-2-sonic-v1:0`** used directly in `ap-northeast-1`. No inference profile needed. Sonic output at 24 kHz; input at 16 kHz. | Build needs `software.amazon.awssdk:netty-nio-client` (HTTP/2) and Reactive Streams `Publisher` for the input; set Netty read and write timeouts above 8 minutes. `usageEvent` carries `details.delta` and `details.total` with `speechTokens` and `textTokens` for input and output (used by the Cost_Estimate). Extra events seen: `userSpeechStart`, `userSpeechEnd`, `completionStart`, `completionEnd`. |
| 2026-11-22 | **Decision** | **GO** for the Sonic design, with four design notes. | (1) Renewal at 80 percent and abrupt-close handling are mandatory. (2) The `INTERRUPTED` target of 200 ms is not met from here (about 330 to 430 ms, 180 to 280 ms net of the round trip): the server should clear the Outbound queue on `userSpeechStart` or `INTERRUPTED`, and the figure must be re-measured from the Tokyo task. (3) Injected text is reliable (10 of 10), so the fixed-audio fallback for "please wait", silence prompts, and goodbyes is not needed; the stored Fallback_Message audio stays only for Bedrock failure. (4) Speculative text precedes audio, so no 300 ms look-ahead buffer. Also: keep silence frames flowing (55 s idle limit), and build history from `FINAL` text only after it has arrived. |
## C-1 Exotel stream protocol via echo server behind ALB (task 1.5)
| Date | Measurement | Result | Notes |
|---|---|---|---|
| | | | |
| | **Decision** | | |

## C-2 Sonic quality for Hindi, English, Hinglish (task 1.6)
| Date | Measurement | Result | Notes |
|---|---|---|---|
| | | | |
| | **Decision** | | |

## C-3 Takeover mechanism (task 1.7)
| Date | Measurement | Result | Notes |
|---|---|---|---|
| | | | |
| | **Decision** | | |
