# End-to-end scenario checklist (task 34.3) - to be run by the User on the M34 5G
Results are blank until the User runs them. Nothing here has been run. Record results here, then summarize in design.md.

## Setup
- Debug build installed, screening on, Agent set as default call handler as configured.
- A second phone places the calls. The number is not in the User's contacts (Unknown_Caller).
- Each call is traced automatically: a debug build writes `trace-<sessionId>.tsv` when the call ends.
  Pull it with `tools/e2e/pull_traces.sh` (files appear in `Android/data/<package>/files/traces/`).
- Columns: `atMs` (ms since the call started), `deltaMs` (gap to previous line), `kind` (EVENT or EFFECT), `name`, `detail`.
- Debug action `CallForegroundService.ACTION_EXPORT_TRACE` re-writes the trace of the current or last call (debug builds only).
  The overlay from 34.1 may expose it as a button; until then trigger it from a debug hook or rely on the automatic write.

## How to read the limits from a trace
Find the named lines and compute the difference in `atMs`. Entry names are the engine's event and effect class names;
check the exact names in the file. The "from" and "to" lines below are the intent, not literal names.

| Limit | Req | From | To | Pass if |
|---|---|---|---|---|
| 1 s contact check | 1.1 | call arrives | contact comparison done | <= 1000 ms |
| 2 s notification | 13.19 | call answered | Live_Call_Screen notification shown | <= 2000 ms |
| 3 s answer | 1.2 | call arrives | `CallAnswered` | <= 3000 ms |
| 2 s greeting | 1.4 | `CallAnswered` | first Speak ("Hello") started | <= 2000 ms |
| 3 s transcript | 2.1 | end of Caller speech | transcript event | <= 3000 ms |
| 1 s classification | 2.2 | transcript event | intent set (state change / next effect) | <= 1000 ms |
| 3 s directions | 3.1 | intent determined | first Speak of directions | <= 3000 ms |
| 2 s transfer start | 4.2 | agent finished transfer message | transfer started or Takeover_Alert raised | <= 2000 ms |
| 2 s stalling entry | 5.1/5.2 | SOLICITATION classified | stalling mode entered | <= 2000 ms |
| 3 s stalling reply | 5.2 | end of Caller utterance | Speak of reply | <= 3000 ms |
| 3 s gate info | 12.3 | end of Caller utterance with Gate_Query | Speak of gate info | <= 3000 ms |
| 2 s control actions | 13.6, 13.10 | User tap (Take over / Mute / Resume / End) | effect (speech stop, bridging phrase, EndCall) | <= 2000 ms |
| 500 ms stop speaking | 13.6, 13.10 | User tap Take over or Mute | `StopSpeaking` effect | <= 500 ms |
| 500 ms language detection | 7.1 | end of Caller utterance | language set on the utterance | <= 500 ms |

The 2 s control-action row covers Resume (bridging phrase) and End call. Take over and Mute must also meet the 500 ms row.

## Scenarios
Run each in Hindi, English and Hinglish. Mark P/F, note the measured ms for the limits that apply, and the trace file name.

| # | Scenario | Hindi | English | Hinglish |
|---|---|---|---|---|
| 1 | Delivery: agent gives directions | | | |
| 2 | Door not opened, Path A enabled (S6 ok, target set, accessibility on) | | | |
| 3 | Door not opened, Path B (Takeover_Alert; User takes over) | | | |
| 4 | Door not opened, Path B, User does not take over (30 s "call again" phrase) | | | |
| 5 | Solicitation: stalling replies, no real PII spoken | | | |
| 6 | Unknown: asks to repeat once, then classification | | | |
| 7 | Gate query during Night_Window (expect Gate_3 only) | | | |
| 8 | Gate query outside Night_Window (expect Gate_1 and Gate_3 as designed) | | | |
| 9 | Gate query during a solicitation call (expect no real gate info) | | | |
| 10 | Caller hangs up mid-call: log saved within 2 s | | | |

For gate scenarios 7 and 8, change the Night_Window setting (or the phone clock) so each call falls on the intended side, and note which you used.

## Per-call record
| Call | Scenario | Language | Trace file | Limits measured (ms) | P/F | Notes |
|---|---|---|---|---|---|---|
| | | | | | | |
