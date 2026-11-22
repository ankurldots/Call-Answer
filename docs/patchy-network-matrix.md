# Patchy-network matrix (task 34.1) - to be run by the User on the M34 5G

Results below are blank until the User runs them. Source of the matrix: design.md "Manual test matrix (patchy network)".
Use a debug build and `tools/netem/*.sh`. Record the overlay values (net / src / latency) per run.

Per profile, run each case in Hindi, English and Hinglish: [ ] = not run, mark P/F and notes.

## Profiles

| # | Profile | Setup | Expected | Result |
|---|---|---|---|---|
| 1 | Good, cloud on | `good.sh` | Cloud stalling replies, response within 3 s | |
| 2 | High latency, cloud on | `high_latency.sh` | DEGRADED; local replies only | |
| 3 | Loss 15%, cloud on | `loss15.sh` | DEGRADED; local replies only | |
| 4 | Flapping, cloud on | `flapping.sh` | Switch within 2 s, no repeated response, no dropped call | |
| 5 | Data off (cellular voice only), cloud on | Mobile data off | UNAVAILABLE; full call flow offline; transfer through cellular | |
| 6 | Airplane / no network | Airplane mode, then reconnect | Outbox entries flushed within 60 s after reconnect | |

## Cases per profile (rows 1-5)

| Case | Hindi | English | Hinglish |
|---|---|---|---|
| Intent: delivery | | | |
| Intent: door not opened | | | |
| Intent: solicitation | | | |
| Intent: unknown | | | |
| Take over | | | |
| Mute | | | |
| Resume | | | |
| End call | | | |
| Quick reply | | | |

Record the filled results in design.md under "Manual test matrix (patchy network)" (User to do; not yet done).
