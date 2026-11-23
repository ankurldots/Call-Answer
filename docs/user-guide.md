# Call Answer: User Guide

Call Answer answers the calls you miss. When you don't pick up, are busy, or are unreachable, your carrier forwards the call to your Exotel number. A cloud Agent then talks to the caller in Hindi or English. The Android companion app shows you what is happening and lets you step in.

This guide is for the person who uses the app. Your administrator (the deployment owner) creates your account and assigns your number. See the [README](../README.md) for how to deploy it.

## What the Agent does

| Caller | What happens |
|---|---|
| Delivery person | The Agent gives directions to your home, starting with the correct gate, and can text the map from your SIM. |
| Someone at your door | The Agent asks them to wait and alerts you. Tap **Take over** to connect them to your mobile. |
| Sales caller (loan, property, other) | The Agent keeps them busy with made-up, plausible answers. It uses none of your real personal data. |
| Unknown reason | The Agent asks why they are calling and takes a message. |
| Same number calling again within the repeat window (default 15 minutes) | The call rings your mobile directly instead of the Agent, and the app shows a ringing alert. |

## Before you start

You need:

- An Android phone (Android 10 or newer) with the app installed.
- An account created by your administrator.
- An Exotel number assigned to you by your administrator.
- A mobile plan that supports conditional call forwarding. The app's code screens were written for Airtel postpaid. Other carriers can use different codes.

## First-time setup

The app guides you through these steps in order.

1. **Sign in** with the account your administrator created.
2. **Accept the privacy consent.** The forwarding codes stay hidden until you accept.
3. **Wait for your number.** If no number is assigned yet, the app checks again every minute while the screen is open.
4. **Enter your mobile number** in international format with the country code. When you take over a call, the caller is connected to this number.
5. **Allow notifications.** The app needs them for caller alerts, the live call notification (with **Take over** and **End call**), and spam-report prompts.
6. **Allow full-screen alerts** (Android 14 and later). This lets the Take over alert appear over the lock screen.
7. **Allow sending SMS** if you want the Agent to text the map to delivery callers. The text goes from your own SIM and your carrier's normal SMS rates apply. If you decline, the Agent still answers but sends no map.
8. **Set up forwarding** (next section).
9. **Open Settings and turn the Agent on.** It can be turned on only after you accepted the consent, have a number, and saved your mobile number.

## Set up call forwarding

Open **Forward unanswered calls** in the app. Your assigned number appears at the top, with three codes:

- **Forward when you do not answer**
- **Forward when busy**
- **Forward when unreachable**

For each one, tap **Open dialer**. The dialer opens with the code filled in. Press the call button yourself to send it. Wait for the network confirmation before moving to the next code.

**Ring time:** The screen shows how many seconds your phone rings before forwarding (default 20). Carriers usually allow 5 to 30 seconds in steps of 5. Longer values, such as 60, are probably not accepted. Check by test call before relying on a longer time.

**Forward every call:** The app can also show a code that forwards all calls. Your phone will not ring at all while it is on. Use it only when you want to, and turn it off afterwards.

**Charges and Wi-Fi calling:** Your carrier may charge for forwarded calls. Wi-Fi calling can change how forwarding works, so test with it on and off.

### Test it

1. Open **Test call** and tap **Send test call**.
2. From another phone, call your number and don't answer.
3. The Agent should pick up. The app waits up to 10 minutes and then shows a pass or a timeout.

If the test times out, check the codes and your carrier's voicemail settings. Carrier voicemail can answer before the forward happens.

### Turn forwarding off

Open **Turn off forwarding**. It has a code for each forwarding type and one code that clears all conditional forwarding at once. Dial them the same way as above.

## During a call

- **Live alert:** When a caller needs you, the app shows an alert, over the lock screen if you allowed full-screen alerts.
- **Take over:** Connects the caller to your mobile. If your mobile is busy, unreachable, or doesn't answer within 30 seconds, the caller returns to the Agent, who tells them you couldn't be reached.
- **End call:** Ends the call from the live call notification.
- **Live transcript:** Shows what the caller and the Agent are saying.

## Repeat callers

If the same number calls again within the repeat window, your phone rings directly instead of the Agent answering. The app shows a ringing alert so you can pick up before the carrier forwards the call.

Settings:

- **Ring me when a recent caller calls again:** turns the feature on or off.
- **Repeat window:** 1 to 60 minutes.
- **Longest ring time:** 30 to 600 seconds.

Sales callers are not rung through.

## Silence the ringer for numbers not in your contacts

This keeps unknown callers from disturbing you while the call rings until your carrier forwards it.

When on:

- A call from a number not in your contacts rings silently. It is not rejected. It stays in your call log and you still see its notification.
- Calls from your contacts ring normally.
- A repeat caller always rings normally.
- Calls with a hidden or private number count as non-contacts and ring silently.

To turn it on:

1. Open Settings and turn on **Silence the ringer for numbers not in my contacts**. It is off by default.
2. Read the explanation, then tap **Allow contacts access**.
3. Grant the call screening role if the app asks. Android gives the app the incoming number only with that role. The app never blocks or answers a call.

Your contacts are checked on the phone only and are never sent anywhere. If the contacts permission is missing, or the check fails or takes more than 3 seconds, the ringer stays on.

Note: your carrier forwards calls from contacts too. This feature only affects your phone's ringer, not what gets forwarded.

## Settings

| Section | What you can set |
|---|---|
| General | Agent name, Agent on or off. |
| Gates and night window | Gate numbers, locations and landmarks, and the night window. The defaults are examples. Change them for your own address. |
| Delivery directions | The steps spoken to a delivery caller, in order. Do not use `{map_location}` in the steps: the map location is texted, not spoken. |
| Voice, disclosure and replies | Female or male voice, English and Hindi voice samples, whether the Agent tells callers it is automated, and quick replies. Changes apply from the next call. |
| Map text message | Map location URL, your address (used only to check an address a caller states, never spoken or sent), and which SIM sends the text. |
| Ring-through | The repeat-caller settings above. |
| Screening | Silence the ringer for non-contacts. |
| Spam reporting | Reports go to 1909 (TRAI). You can send them without opening your messaging app, which needs the Send SMS permission. |

If a field is invalid, the app shows **Not saved** and keeps your previous values.

## Call log

Each call is saved with its time, caller number, outcome, cost estimate and transcript. After a call, the app can prompt you to report it as spam.

## Privacy and recording

- The Agent plays a recording notice and can say it is automated. You control the second one in Settings.
- Call audio is processed in AWS Tokyo, outside India.
- Recording calls may need consent from everyone on the call under the law where you live. You are responsible for following it.

## Troubleshooting

| Problem | What to check |
|---|---|
| No forwarding codes shown | Accept the privacy consent and make sure a number is assigned. |
| Test call times out | Re-dial the codes. Check carrier voicemail, ring time, and Wi-Fi calling. |
| Agent won't turn on | Accept the consent, get a number assigned, and save your mobile number. |
| Take over alert doesn't appear on the lock screen | Allow full-screen notifications for the app. |
| Map text not sent | Allow the Send SMS permission, check the map location URL and the SIM selected. |
| Unknown callers still ring loudly | Turn on the silence setting, allow contacts access, and confirm the call screening role. |
| Settings page won't open | Some phones lack the system page. Open it from Android's app settings. |
