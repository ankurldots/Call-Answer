# Requirements Document

## Introduction

The AI Call Screening Agent is a native Android app (Kotlin) that screens incoming phone calls from numbers not saved in the user's contacts. The Agent answers such a call, greets the caller with "Hello", listens to the caller, and classifies the caller's intent:

- **Delivery**: the Agent gives the caller directions to the user's home, starting with which colony gate to use.
- **Door not answered**: the Agent transfers the call to a real person.
- **Solicitation** (loan offer, property sale, or any other sales pitch): the Agent keeps the caller engaged with fictitious information until the caller hangs up.

The Agent speaks Hindi or English, matching the caller's language. The Agent must keep working on patchy or poor network connections.

The Agent also tells callers which colony gate to use. From 22:30 to 05:30 only colony door no 3 (opposite Aurobindo College) is open. At all other times vendors enter through Gate no 1 on Press Enclave Road, near Punjab National Bank.

### Platform constraints to resolve in design

- Android does not give third-party apps direct access to the audio of a cellular call. Injecting synthesized speech into the call and capturing the caller's voice requires a design decision (for example, speakerphone with microphone loopback, a default-dialer role, or routing calls through a VoIP/SIP number). The design phase must select and validate one approach.
- Answering a call programmatically requires the ANSWER_PHONE_CALLS permission, and reading contacts requires READ_CONTACTS. Call screening requires the call screening role.
- Call recording is done by the phone's built-in call recorder (the Samsung Phone app auto-record, enabled for unknown numbers by the User in the phone's settings), not by the App. No public API exists to start or stop that recorder, and the App cannot control its file format, start time, or storage location. The App only finds the finished Phone_Recording and links it to the Call_Log entry. The design phase must verify that the recorder starts for calls answered through the call screening role, and must verify whether the Agent's speech is captured in the Phone_Recording.
- The Agent's calls are answered on the phone's speakerphone so that the User can hear the call and speak into it. Because the Agent's synthesized speech then plays through the phone's loudspeaker, and the Caller's audio is captured through the phone's microphone, the design must handle the Agent's own voice being picked up by the microphone (echo) so that the Agent's speech is not treated as Caller speech. The design must also decide how the User's own speech is separated from the Caller's speech in the transcript.
- Because the connection may be poor, the design should favor on-device speech recognition, language identification, and speech synthesis, with cloud services used only as an optional enhancement.
- The target device is a Samsung Galaxy M34 5G (Exynos 1280, One UI, India model) with the Samsung Phone app as the default dialer. The on-device models and all timing limits in these requirements must be validated on this device.

## Glossary

- **Agent**: The automated voice assistant inside the App that converses with the caller.
- **App**: The Android application (Kotlin) that contains the Agent and all supporting components.
- **User**: The person who owns the phone and installed the App.
- **Caller**: The person placing the incoming call.
- **Unknown_Caller**: A Caller whose phone number does not match any entry in the User's contacts.
- **Known_Caller**: A Caller whose phone number matches an entry in the User's contacts.
- **Call_Screener**: The App component that decides whether the Agent handles an incoming call.
- **Intent_Classifier**: The App component that assigns one Intent_Category to the Caller's speech.
- **Intent_Category**: One of DELIVERY, DOOR_NOT_OPENED, SOLICITATION, or UNKNOWN.
- **Delivery_Directions**: The User-configurable ordered list of Delivery_Steps spoken to a delivery Caller. The default steps are: go to the Map_Location, take the stairs in the middle of the house, go to the second floor, and ring the bell.
- **Delivery_Step**: One instruction step of the Delivery_Directions, with a text in English and a text in Hindi. A Delivery_Step text may contain the placeholder {map_location}.
- **Map_Location**: The address or map coordinates of the User's home, configured by the User in the App.
- **Transfer_Target**: The phone number of a real person, configured by the User in the App, to which a call is transferred.
- **Stalling_Mode**: The Agent behavior of giving fictitious, plausible-sounding information to keep a Caller engaged.
- **Fictitious_Information**: Invented details that contain no real personal, financial, or location data of the User.
- **Language_Detector**: The App component that identifies whether the Caller speaks Hindi or English.
- **Speech_Recognizer**: The App component that converts the Caller's speech to text.
- **Speech_Synthesizer**: The App component that converts Agent text to speech in Hindi or English.
- **Network_Monitor**: The App component that measures connectivity quality (available, degraded, or unavailable).
- **Offline_Mode**: The operating state in which the App uses only on-device components.
- **Call_Log**: The App's stored record of screened calls, including transcript, Intent_Category, language, and outcome.
- **Phone_Recording**: An audio file created by the phone's built-in call recorder for a screened call, in the format chosen by the phone. The App does not create, start, or stop Phone_Recordings.
- **Recording_Folder**: The folder the User selected in the App where the phone saves recordings.
- **Link_Window**: The time tolerance for matching a Phone_Recording to a call, 60 seconds around the call start and end.
- **Silence_Timeout**: The configured duration of Caller silence after which the Agent re-prompts, with a default of 8 seconds.
- **Gate_Query**: A Caller utterance asking how or where to enter the colony, or which colony gate to use. A Gate_Query is a sub-intent detected alongside the Intent_Category and does not change the Intent_Category.
- **Colony_Gate_Info**: The spoken statement telling the Caller which colony gate to use, consisting of the gate number, the gate's location description, and the gate's landmark.
- **Gate_1**: The colony gate on Press Enclave Road, near Punjab National Bank, used outside the Night_Window. These values are the defaults and are configurable by the User.
- **Gate_3**: The colony gate (colony door no 3) opposite Aurobindo College, the only gate open during the Night_Window. These values are the defaults and are configurable by the User.
- **Night_Window**: The daily period from 22:30 (inclusive) to 05:30 (exclusive) in Device_Local_Time, which crosses midnight. These times are the defaults and are configurable by the User.
- **Device_Local_Time**: The current time of day (24-hour clock) according to the time zone set on the User's phone.
- **Disclosure_Setting**: The User setting that turns the Agent's spoken disclosure on or off, off by default.
- **Voice_Setting**: The User setting that selects the gender of the Agent's synthesized voice, male or female, with female as the default.
- **Speaker_Setting**: The User setting that turns the automatic speakerphone on for answered calls on or off, on by default.
- **Live_Call_Screen**: The App's notification and screen shown during a call, displaying the live transcript, the detected Intent_Category and language, and the call controls.
- **Take_Over**: The state in which the Agent has stopped speaking and generating responses so that the User talks to the Caller directly, entered with the "Take over" control. The call is not transferred.
- **Takeover_Alert**: A high-priority alert on the User's phone (a full-screen notification with sound and vibration, audible on the speaker) that asks the User to take over the call, with a "Take over" control.
- **Agent_Muted**: The state in which the Agent does not speak but keeps listening, entered with the "Mute Agent" control.
- **Quick_Reply**: A short phrase, from a User-configured list (defaults: "Please wait", "I am coming"), that the User can send during a call for the Agent to speak.
- **Bridging_Phrase**: A fixed short phrase (for example "One moment") that the Agent speaks in the Caller's language when the User chooses "Resume Agent".
- **Echo**: The Agent's own synthesized speech picked up by the phone's microphone while the speakerphone is on.
- **Spam_Report**: A complaint about a Caller sent as one SMS to the TRAI_Report_Number.
- **TRAI_Report_Number**: The SMS complaint number 1909 of the Telecom Regulatory Authority of India. The value is fixed at 1909 and the User cannot edit the value.
- **Spam_Report_Message**: The text of a Spam_Report, in the format "<Brief_Description>, <Sender_Number>, <DD/MM/YY>", where the three parts are separated by a comma and a single space.
- **Brief_Description**: The first part of a Spam_Report_Message, a short text of 1 or more printable ASCII characters that contains no comma and describes the spam call (for example "Unsolicited loan offer call").
- **Sender_Number**: The second part of a Spam_Report_Message, the Caller's number as recorded in the Call_Log entry, consisting of 7 to 15 digits with an optional leading "+".
- **Report_Date**: The third part of a Spam_Report_Message, the date of the call start in the format DD/MM/YY, converted to the time zone set on the User's phone.
- **Report_Status**: The Call_Log field with one value of: not reported, prepared, handed to messaging app, sent, failed, or cannot report - number unavailable.
- **Silent_Send_Setting**: The User setting "Send spam reports without confirmation" that turns direct sending of Spam_Reports on or off, off by default.

## Requirements

### Requirement 1: Identify and answer calls from unknown numbers

**User Story:** As a User, I want the App to answer calls from numbers that are not in my contacts, so that I do not have to take calls from strangers myself.

#### Acceptance Criteria

1. WHEN an incoming call arrives, THE Call_Screener SHALL compare the Caller's phone number with the User's contacts and complete the comparison within 1 second of the call arriving.
2. WHILE screening is enabled, WHEN the Caller is an Unknown_Caller, THE Call_Screener SHALL answer the call with the Agent within 3 seconds of the call arriving.
3. WHEN the Caller is a Known_Caller, THE Call_Screener SHALL leave the call ringing for the User and SHALL NOT answer the call with the Agent.
4. WHEN the Agent answers a call, THE Agent SHALL speak the greeting "Hello" within 2 seconds of answering.
5. IF the Caller's phone number is hidden or unavailable, THEN THE Call_Screener SHALL treat the Caller as an Unknown_Caller.
6. IF the App cannot read the User's contacts, THEN THE Call_Screener SHALL leave the call ringing for the User, SHALL NOT answer the call with the Agent, and SHALL notify the User within 5 seconds that contact access is required.
7. THE App SHALL provide a setting that enables or disables screening, with screening enabled by default, and SHALL retain the setting value across App restarts.
8. WHEN the Caller's phone number differs from a contact number only by country code or formatting characters (spaces, hyphens, parentheses, periods, or a leading plus sign), THE Call_Screener SHALL treat the Caller as a Known_Caller.
9. WHILE screening is disabled, WHEN an incoming call arrives, THE Call_Screener SHALL leave the call ringing for the User and SHALL NOT answer the call with the Agent.

### Requirement 2: Classify the Caller's intent

**User Story:** As a User, I want the Agent to understand why the Caller is calling, so that the Agent can respond appropriately.

#### Acceptance Criteria

1. WHEN the Caller finishes speaking after the greeting, THE Speech_Recognizer SHALL produce a transcript of the Caller's speech within 3 seconds of the end of the Caller's speech.
2. WHEN a transcript is available, THE Intent_Classifier SHALL assign exactly one Intent_Category (DELIVERY, DOOR_NOT_OPENED, SOLICITATION, or UNKNOWN) to the transcript within 1 second.
3. WHEN the Caller states that the Caller has come for a delivery, THE Intent_Classifier SHALL assign the DELIVERY category.
4. WHEN the Caller states that nobody is opening the door, THE Intent_Classifier SHALL assign the DOOR_NOT_OPENED category.
5. WHEN the Caller offers a loan, offers to sell property, or offers to sell any other product or service, THE Intent_Classifier SHALL assign the SOLICITATION category.
6. IF the transcript matches no defined category, THEN THE Intent_Classifier SHALL assign the UNKNOWN category.
7. IF the transcript matches both DELIVERY and SOLICITATION, THEN THE Intent_Classifier SHALL assign the category whose matching phrase starts at the lowest character position in the transcript.
8. WHEN the Caller makes a new utterance during the call that yields a different Intent_Category than the current one, THE Intent_Classifier SHALL reclassify using only that most recent utterance, and THE Agent SHALL follow the behavior of the new category from its next response onward.
9. THE Intent_Classifier SHALL classify an utterance without requiring a network connection.
10. IF the Speech_Recognizer produces an empty transcript or fails to produce a transcript within 3 seconds, THEN THE Intent_Classifier SHALL assign the UNKNOWN category, and THE Agent SHALL ask the Caller once to repeat the request.
11. WHEN a transcript contains a Gate_Query, THE Intent_Classifier SHALL flag the utterance as a Gate_Query within 1 second and SHALL NOT change the Intent_Category because of the Gate_Query.
12. WHEN a transcript contains a Gate_Query and also matches an Intent_Category, THE Intent_Classifier SHALL assign that Intent_Category using criteria 3 to 7 and SHALL flag the Gate_Query in addition.

### Requirement 3: Handle delivery callers

**User Story:** As a User, I want delivery people to receive directions to my home automatically, so that deliveries succeed when I am not available to answer.

#### Acceptance Criteria

1. WHEN the Intent_Category is DELIVERY, THE Agent SHALL begin speaking the Delivery_Directions in the Caller's language within 3 seconds of the Intent_Category being determined.
2. THE Delivery_Directions SHALL consist of the configured Delivery_Steps spoken in the configured order, and the default Delivery_Steps SHALL instruct the Caller, in this order, to go to the Map_Location, take the stairs in the middle of the house, go to the second floor, and ring the bell.
3. WHEN the Caller asks for the directions to be repeated, THE Agent SHALL repeat the complete Delivery_Directions, up to 3 repetitions per call.
4. WHERE the User has configured a Map_Location, WHEN the Agent finishes speaking the Delivery_Directions, THE App SHALL send the Map_Location to the Caller as a text message within 30 seconds.
5. IF the Map_Location is not configured, THEN THE Agent SHALL speak the Delivery_Directions without a location reference and THE App SHALL notify the User within 60 seconds that the Map_Location is missing.
6. WHEN the Agent has spoken the Delivery_Directions and the Caller has verbally acknowledged, ended the call, or been silent for 10 seconds, THE Agent SHALL end the call.
7. IF the Caller's language cannot be determined or is not supported, THEN THE Agent SHALL speak the Delivery_Directions in the User's default language.
8. IF the text message containing the Map_Location is not delivered within 30 seconds of being sent, THEN THE App SHALL notify the User within 60 seconds that the text message failed, and THE Agent SHALL continue the call without interruption.
9. IF the Caller asks for the directions to be repeated after 3 repetitions have been spoken, THEN THE Agent SHALL tell the Caller that no further repetitions are available and SHALL end the call.
10. WHEN the Agent speaks the Delivery_Directions, THE Agent SHALL speak the Colony_Gate_Info selected under Requirement 11 first, before the instruction to go to the Map_Location, and each repetition of the Delivery_Directions SHALL begin with the Colony_Gate_Info selected at the time of that repetition.
11. WHEN the Caller makes a Gate_Query while the Agent is speaking or has spoken the Delivery_Directions, THE Agent SHALL answer under Requirement 11, and the answer SHALL NOT count as one of the 3 repetitions in criterion 3.

### Requirement 4: Transfer calls to a real person

**User Story:** As a User, I want callers who cannot get into my home to be connected to a real person, so that they receive immediate human help.

#### Acceptance Criteria

1. WHEN the Intent_Category is DOOR_NOT_OPENED, THE Agent SHALL tell the Caller, in the Caller's language, that the Agent is transferring the call to a person.
2. WHEN the Agent has finished telling the Caller about the transfer, THE App SHALL start the transfer within 2 seconds, by connecting the Transfer_Target to the call WHERE the phone supports connecting a second call into the call, and otherwise by raising the Takeover_Alert.
3. IF the Transfer_Target does not answer within 30 seconds of the transfer starting and the User does not take over within 30 seconds of the Takeover_Alert raised under criterion 7, THEN THE Agent SHALL tell the Caller, in the Caller's language, that nobody is available and ask the Caller to call again shortly, and THE App SHALL keep the Caller connected to the Agent.
4. IF connecting the Transfer_Target fails, THEN THE App SHALL notify the User within 10 seconds that connecting the Transfer_Target failed, including the Caller's number, and THE App SHALL continue the transfer with the Takeover_Alert as specified in criterion 7, with the request to call again made only under criterion 9.
5. IF the Transfer_Target is not configured, THEN THE App SHALL raise the Takeover_Alert within 2 seconds of the Agent finishing telling the Caller about the transfer, THE App SHALL notify the User within 10 seconds that a Transfer_Target is not configured, including the Caller's number, and THE Agent SHALL ask the Caller, in the Caller's language, to call again shortly only if the User does not take over within 30 seconds of the Takeover_Alert, as specified in criterion 9.
6. WHEN a transfer starts, THE App SHALL notify the User within 10 seconds that a call was transferred, including the Caller's number.
7. IF connecting the Transfer_Target fails or the Transfer_Target does not answer within 30 seconds, THEN THE App SHALL raise the Takeover_Alert within 2 seconds and THE Agent SHALL keep the Caller on the line with a short "please wait" message in the Caller's language at intervals of no more than 15 seconds.
8. WHEN the User activates "Take over" from the Takeover_Alert, THE App SHALL enter Take_Over as specified in Requirement 13 and SHALL record the transfer as completed by the User in the Call_Log.
9. IF the User does not take over within 30 seconds of the Takeover_Alert, THEN THE Agent SHALL tell the Caller, in the Caller's language, that nobody is available and ask the Caller to call again shortly, and THE App SHALL keep the Takeover_Alert visible until the call ends.
10. IF the Transfer_Target is not configured, THEN THE App SHALL raise the Takeover_Alert instead of rejecting the transfer.
11. WHEN the App raises the Takeover_Alert under criterion 2 or criterion 5, THE Agent SHALL keep the Caller on the line with a short "please wait" message in the Caller's language at intervals of no more than 15 seconds until the User takes over or criterion 9 applies.

### Requirement 5: Keep solicitation callers busy

**User Story:** As a User, I want loan, property, and sales callers kept occupied with harmless information, so that they waste their time instead of mine.

#### Acceptance Criteria

1. WHEN the Intent_Category is classified as SOLICITATION, THE Agent SHALL enter Stalling_Mode within 2 seconds of the classification.
2. WHILE the Agent is in Stalling_Mode, THE Agent SHALL respond to each Caller utterance with Fictitious_Information in the language the Caller used in that utterance, within 3 seconds of the end of the utterance.
3. WHILE the Agent is in Stalling_Mode, THE Agent SHALL continue the conversation until the Caller ends the call, the maximum stalling duration set by the User is reached, or the User takes over the call under Requirement 13.
4. THE Agent SHALL generate Fictitious_Information that contains none of the User's real name, phone number, street address, email address, date of birth, government-issued ID number, or bank, card, or account numbers.
5. WHILE the Agent is in Stalling_Mode, THE Agent SHALL end each response with exactly one follow-up question related to the Caller's most recent utterance.
6. IF the Caller is silent for longer than the Silence_Timeout during Stalling_Mode, THEN THE Agent SHALL prompt the Caller to continue, and IF the Caller remains silent for 3 consecutive prompts, THEN THE Agent SHALL end the call politely and record the reason as caller silence in the Call_Log.
7. WHEN the Caller asks the Agent for the User's real personal or financial information, THE Agent SHALL respond with Fictitious_Information and SHALL NOT disclose any real data of the User.
8. WHERE the User sets a maximum stalling duration (1 to 60 minutes), THE Agent SHALL end the call politely within 10 seconds of that duration being reached and SHALL record the reason as maximum duration reached in the Call_Log.
9. WHEN the Caller ends the call during Stalling_Mode, THE App SHALL record the stalling duration, in seconds from entry into Stalling_Mode to call end, in the Call_Log within 5 seconds of call end.
10. IF the Agent fails to generate a response during Stalling_Mode, THEN THE Agent SHALL say a fixed Fictitious_Information filler phrase and retry once, and IF the retry also fails, THEN THE Agent SHALL end the call politely and record the failure in the Call_Log.

### Requirement 6: Handle unclear or unrecognized speech

**User Story:** As a User, I want the Agent to cope with callers it cannot understand, so that no call ends in confusion.

#### Acceptance Criteria

1. WHEN the Intent_Category is UNKNOWN, THE Agent SHALL ask the Caller to state the reason for the call.
2. IF the Intent_Category remains UNKNOWN after 2 consecutive clarification prompts, THEN THE Agent SHALL tell the Caller that the User will return the call and end the call.
3. WHEN the Agent ends a call with the UNKNOWN category, THE App SHALL notify the User with the Caller's number and the full transcript of the call, and the notification SHALL be delivered within 30 seconds of the call ending.
4. IF the Caller is silent for longer than the Silence_Timeout after the greeting, THEN THE Agent SHALL repeat the greeting once.
5. IF the Caller remains silent for a second Silence_Timeout after the repeated greeting, THEN THE Agent SHALL end the call.
6. IF the Caller's number is unavailable when the Agent ends a call with the UNKNOWN category, THEN THE App SHALL notify the User with the transcript and an indication that the number is unavailable.
7. WHEN the Agent ends a call because of two consecutive Silence_Timeout periods, THE App SHALL notify the User with the Caller's number and an indication that the Caller was silent.

### Requirement 7: Respond in the Caller's language

**User Story:** As a User, I want the Agent to speak Hindi or English according to the Caller, so that callers can understand the Agent.

#### Acceptance Criteria

1. WHEN the Caller finishes an utterance, THE Language_Detector SHALL classify the utterance as Hindi or English within 500 milliseconds of the end of the utterance.
2. WHEN the Language_Detector identifies Hindi, THE Agent SHALL respond in Hindi.
3. WHEN the Language_Detector identifies English, THE Agent SHALL respond in English.
4. WHEN the Language_Detector identifies a language different from the language of the previous identified utterance, THE Agent SHALL respond in the new language starting with the next response and for all subsequent responses until the language changes again.
5. WHEN the Caller mixes Hindi and English in one utterance, THE Language_Detector SHALL select the language that accounts for more than 50% of the words in the utterance, and SHALL select the language of the first word if both languages account for exactly 50%.
6. IF the Language_Detector cannot identify the language of an utterance, THEN THE Agent SHALL respond in the language of the most recent identified utterance, or in English if no utterance has been identified in the call.
7. THE Intent_Classifier SHALL recognize each Intent_Category in Hindi and English transcripts, including Hindi transcripts in Devanagari script and Hindi transcripts in Roman script (Hinglish).
8. THE Speech_Synthesizer SHALL produce Hindi speech and English speech.
9. IF the Speech_Synthesizer fails to produce speech in the selected language, THEN THE Agent SHALL retry once, and if the retry fails, THE Agent SHALL tell the Caller in the last successfully used language that it cannot respond and SHALL keep the call open.
10. THE Speech_Synthesizer SHALL speak every Agent utterance in the voice gender selected by the Voice_Setting, in both Hindi and English.
11. THE Speech_Synthesizer SHALL keep the same voice gender for the whole call, including after a change of language under criterion 4.
12. IF the voice of the selected gender is not available on the device for the selected language, THEN THE Speech_Synthesizer SHALL use the closest available voice of the same gender for that language and THE App SHALL record the substitution in the Call_Log.
13. IF no voice of the selected gender is available on the device for the selected language, THEN THE Speech_Synthesizer SHALL use any available voice for that language and THE App SHALL record the substitution in the Call_Log.
14. THE App SHALL provide the pre-recorded fallback phrases of Requirement 8 criterion 9 in both a male voice and a female voice, and THE Agent SHALL speak each fallback phrase in the voice gender selected by the Voice_Setting.
15. THE Speech_Synthesizer SHALL speak in the voice selected by the Voice_Setting using only on-device voices bundled with the App, with the device network connection disabled.

### Requirement 8: Operate under patchy or poor network conditions

**User Story:** As a User, I want the Agent to keep working when my network is weak or drops, so that calls are not missed or broken by connectivity problems.

#### Acceptance Criteria

1. WHILE a call is in progress, THE Network_Monitor SHALL classify connectivity as available, degraded, or unavailable at intervals of no more than 2 seconds, where degraded means round-trip latency above 1.5 seconds or packet loss above 10 percent over the last 2 seconds, and unavailable means no successful network round trip within the last 2 seconds.
2. WHILE connectivity is unavailable or degraded, THE App SHALL operate in Offline_Mode using on-device Speech_Recognizer, Language_Detector, Intent_Classifier, and Speech_Synthesizer.
3. WHERE connectivity is available, THE App SHALL be permitted to use cloud services to improve recognition or response quality.
4. IF a cloud request does not return within 1.5 seconds, THEN THE App SHALL discard the request, use the on-device result for that request, and continue the call without notifying the Caller.
5. WHEN the Network_Monitor classification changes during a call, THE App SHALL switch between cloud and on-device processing within 2 seconds, without dropping the call and without repeating a completed Agent response.
6. WHEN the Caller finishes speaking while the App is in Offline_Mode, THE Agent SHALL begin its response within 3 seconds.
7. IF the Speech_Recognizer cannot produce a transcript of the Caller's last statement because the audio is interrupted or garbled, THEN THE Agent SHALL ask the Caller to repeat the last statement in the Caller's last detected language, and SHALL ask at most 3 consecutive times before treating the call as interrupted under criterion 8.
8. IF no usable Caller audio is received for more than 10 consecutive seconds, THEN THE Agent SHALL end the call, and THE App SHALL record the interruption, including its start time and duration, in the Call_Log.
9. IF the Agent fails to complete a response due to a processing error, THEN THE Agent SHALL speak a pre-recorded fallback phrase in the Caller's last detected language within 3 seconds of the failure, and the call SHALL remain active.
10. IF connecting the Transfer_Target under Requirement 4 requires network connectivity and connectivity is unavailable, THEN THE App SHALL attempt the connection through the cellular voice network, and IF the attempt fails, THEN THE App SHALL raise the Takeover_Alert as specified in Requirement 4 criterion 7, which requires no network connectivity, and THE App SHALL record the failure in the Call_Log.
11. THE App SHALL bundle the on-device models for Hindi and English, including a male voice and a female voice for each language, so that Offline_Mode is usable immediately after installation, with no network connection and no additional download required.
12. WHEN connectivity returns after one or more calls were handled while connectivity was unavailable, THE App SHALL deliver the User notifications and Call_Log entries for those calls no later than 60 seconds after connectivity returns, and SHALL retain them on the device until delivered.

### Requirement 9: Configuration and permissions

**User Story:** As a User, I want to configure the Agent and grant required permissions, so that the Agent works with my details and my consent.

#### Acceptance Criteria

1. THE App SHALL allow the User to configure the Map_Location, the Transfer_Target, the screening enabled setting (on or off, default on), and the maximum stalling duration (whole seconds from 30 to 600, default 120).
2. WHEN the App is first launched, THE App SHALL request the call screening role and the permissions for contacts, phone answering, call control, microphone, notifications, and sending text messages, and SHALL display a purpose explanation for each before its request is shown. The App SHALL NOT request broad storage access for recordings, and SHALL obtain access to Phone_Recordings only through the Recording_Folder selection in criterion 11.
3. IF the User denies a required permission, THEN THE App SHALL disable the dependent feature, display a message within 2 seconds naming the disabled feature and the denied permission, and keep all other configuration values unchanged.
4. IF the User enters a Transfer_Target that is not a valid phone number (digits with an optional leading "+", 7 to 15 digits, no other characters), THEN THE App SHALL reject the entry, show a validation message indicating the number format is invalid, and keep the previously saved Transfer_Target unchanged.
5. WHEN the User saves a valid configuration, THE App SHALL persist the values on the device so that they are still present after the App is closed and relaunched.
6. IF the User enters a maximum stalling duration outside 30 to 600 seconds or a non-numeric value, THEN THE App SHALL reject the entry, show a validation message stating the allowed range, and keep the previously saved value unchanged.
7. WHEN the User opens the App after previously denying a required permission, THE App SHALL show the denied permissions and provide a control to request them again or open the system settings for the App.
8. THE App SHALL allow the User to configure the Night_Window start time and end time (24-hour HH:MM), and for each of Gate_1 and Gate_3 the gate number, the location description, and the landmark, with defaults of 22:30, 05:30, Gate_1 (Press Enclave Road, near Punjab National Bank) and Gate_3 (opposite Aurobindo College).
9. IF the User enters a Night_Window time that is not a valid 24-hour HH:MM value, or a gate number, location description, or landmark that is empty or longer than 100 characters, THEN THE App SHALL reject the entry, show a validation message stating the allowed format, and keep the previously saved value unchanged.
10. IF the User sets the Night_Window start time equal to the end time, THEN THE App SHALL reject the entry, show a validation message stating that the start and end times must differ, and keep the previously saved values unchanged.
11. THE App SHALL allow the User to select the Recording_Folder through the system folder picker, SHALL persist the selection so that the selection is still present after the App is closed and relaunched, and SHALL request access only to that folder.
12. WHEN the User first opens the recording settings, THE App SHALL show a one-time instruction to enable auto-record for unknown numbers in the phone's call recording settings, because the App cannot enable the phone's recorder.
13. WHILE no Recording_Folder is selected, THE App SHALL show a warning that Phone_Recordings cannot be linked to the Call_Log and SHALL provide a control to select the Recording_Folder.
14. THE App SHALL allow the User to configure the Disclosure_Setting (on or off, default off), SHALL persist the value on the device so that the value is still present after the App is closed and relaunched, and SHALL apply a changed value starting with the next call.
15. THE App SHALL allow the User to configure the Voice_Setting (male or female, default female), SHALL provide a control that plays a short sample in the selected voice and language, SHALL persist the value on the device so that the value is still present after the App is closed and relaunched, and SHALL apply a changed value starting with the next call, with a call in progress keeping its voice.
16. THE App SHALL allow the User to add, edit, delete, and reorder Delivery_Steps, with 1 to 10 Delivery_Steps in the list and 1 to 200 characters in each Delivery_Step text, and SHALL provide a control to reset the Delivery_Directions to the default Delivery_Steps.
17. WHEN the User adds or edits a Delivery_Step, THE App SHALL let the User enter the English text and the Hindi text of the Delivery_Step.
18. IF the User saves a Delivery_Step with the text in only one language, THEN THE App SHALL tell the User which language text is missing and SHALL still save the Delivery_Step.
19. WHILE a Delivery_Step has text in only one language, WHEN the Agent speaks that Delivery_Step in the language with the missing text, THE Agent SHALL speak the provided text translated by the App's on-device translation if the on-device translation is available, and otherwise THE Agent SHALL speak the provided text as is.
20. IF the User saves a Delivery_Step list with no Delivery_Steps, or a Delivery_Step with text longer than 200 characters or with no text in either language, or more than 10 Delivery_Steps, THEN THE App SHALL reject the entry, show a validation message stating the allowed limits, and keep the previously saved list unchanged.
21. THE App SHALL provide a control that plays a sample of the full Delivery_Directions in the selected voice and language.
22. WHEN the User saves a valid Delivery_Step list, THE App SHALL persist the list on the device so that the list is still present after the App is closed and relaunched, and SHALL apply the list starting with the next call, with a call in progress keeping its list.
23. WHEN the Agent speaks a Delivery_Step containing the placeholder {map_location}, THE Agent SHALL replace the placeholder with the Map_Location.
24. WHEN the Agent speaks the Delivery_Directions, THE Agent SHALL speak the Colony_Gate_Info before the first Delivery_Step, as specified in Requirement 3 criterion 10.
25. THE App SHALL allow the User to configure the Speaker_Setting (on or off, default on), SHALL persist the value on the device so that the value is still present after the App is closed and relaunched, and SHALL apply a changed value starting with the next call.
26. THE App SHALL allow the User to configure the Silent_Send_Setting (on or off, default off), SHALL persist the value on the device so that the value is still present after the App is closed and relaunched, and SHALL apply a changed value starting with the next Spam_Report, as specified in Requirement 14.

### Requirement 10: Call log and privacy

**User Story:** As a User, I want a record of what the Agent handled, so that I can review calls and trust the Agent.

#### Acceptance Criteria

1. WHEN a screened call ends, THE App SHALL save a Call_Log entry containing the Caller's number, start time (date and time with timezone), duration (whole seconds, 0 to 86,400), detected language, final Intent_Category, transcript, and outcome within 2 seconds of call end.
2. IF the Caller's number is withheld or unavailable, THEN THE App SHALL save the Call_Log entry with the Caller's number recorded as unknown.
3. IF saving a Call_Log entry fails, THEN THE App SHALL retry the save up to 3 times at 1-second intervals, and if all retries fail, THE App SHALL show the User a notification that the call was not logged.
4. WHEN the User opens the Call_Log, THE App SHALL display all entries in reverse chronological order by start time, with the most recent entry first.
5. WHEN the User confirms deletion of a Call_Log entry, THE App SHALL remove the entry and its transcript from the device within 2 seconds and SHALL ask the User whether to also delete the linked Phone_Recording, with the default answer no. WHERE the User answers yes, THE App SHALL delete the Phone_Recording within 2 seconds, and otherwise THE App SHALL leave the file on the phone.
6. THE App SHALL store transcripts and Call_Log entries only on the device, and THE App SHALL NOT copy Phone_Recordings anywhere.
7. IF a cloud service is used, THEN THE App SHALL send only the audio or text required for that request and SHALL NOT retain any copy of the audio, text, or transcript outside the device after the request completes.
8. FOR ALL Call_Log entries, serializing an entry to storage and then reading the entry back SHALL produce an equivalent entry in which every field has the same value as the original, including the recording status and the Phone_Recording file reference, duration, and size (round-trip property).
9. WHEN the Agent speaks Colony_Gate_Info during a call, THE App SHALL record in the Call_Log entry each gate number stated, whether the statement was proactive or in response to a Gate_Query, and whether it was real or fictitious, within 2 seconds of call end.
10. THE App SHALL record in each Call_Log entry a recording status of linked, not found, no folder selected, deleted, or folder access lost, as specified in Requirement 12, together with the Phone_Recording file reference, duration, and size when the status is linked.
11. WHILE the Disclosure_Setting is on, THE Agent SHALL tell the Caller, in the Caller's language, within the first 5 seconds of the call that the call is recorded and handled by an automated assistant, and this statement SHALL NOT depend on whether the phone is recording, because the App cannot know whether the phone is recording; WHILE the Disclosure_Setting is off, THE Agent SHALL NOT make this statement.
12. WHEN a screened call ends, THE App SHALL record in the Call_Log entry the voice gender used (male or female) and whether a voice substitution occurred under Requirement 7 criterion 12 or 13, within 2 seconds of call end.
13. THE App SHALL record in each Call_Log entry the Report_Status (default "not reported") and, when a Spam_Report has been prepared, the report date and time (date and time with timezone), as specified in Requirement 14, and the Report_Status and report date SHALL be included in the round-trip property of criterion 8.

### Requirement 11: Tell callers which colony gate to use

**User Story:** As a User, I want callers to be told the correct colony gate for the time of day, so that vendors and visitors enter through the open gate and reach my home.

#### Acceptance Criteria

1. WHEN a Gate_Query is flagged and the Intent_Category is not SOLICITATION, THE Agent SHALL begin speaking the Colony_Gate_Info in the Caller's language within 3 seconds of the end of the Caller's utterance.
2. WHILE Device_Local_Time is within the Night_Window, THE Agent SHALL state Gate_3 as the gate to use, including its location description and landmark.
3. WHILE Device_Local_Time is outside the Night_Window, THE Agent SHALL state Gate_1 as the gate to use, including its location description and landmark.
4. WHEN Device_Local_Time is exactly the Night_Window start time, THE Agent SHALL state Gate_3, and WHEN Device_Local_Time is exactly the Night_Window end time, THE Agent SHALL state Gate_1. The Night_Window is inclusive of its start time and exclusive of its end time.
5. WHEN the Agent begins speaking a Colony_Gate_Info statement, THE Agent SHALL read Device_Local_Time at that moment, to the minute, and use it to select the gate for that statement.
6. WHEN a call continues past the Night_Window start time or end time, THE Agent SHALL state the gate that applies at the moment each later Colony_Gate_Info statement begins and SHALL NOT interrupt or alter a statement already in progress.
7. WHEN the Caller asks for the Colony_Gate_Info to be repeated, THE Agent SHALL repeat it using the gate that applies at the moment the repetition begins, with no limit on repetitions other than the call ending.
8. WHEN the Intent_Category is DELIVERY, THE Agent SHALL state the Colony_Gate_Info as part of the Delivery_Directions as specified in Requirement 3, even if the Caller made no Gate_Query.
9. WHEN a Gate_Query is flagged and the Intent_Category is DOOR_NOT_OPENED, THE Agent SHALL speak the Colony_Gate_Info and then continue with the transfer behavior of Requirement 4.
10. WHEN a Gate_Query is flagged in an utterance and the Intent_Category is UNKNOWN, THE Agent SHALL speak the Colony_Gate_Info and then ask the Caller to state the reason for the call, and the Gate_Query utterance SHALL NOT count as an unresolved clarification prompt under Requirement 6 criterion 2.
11. WHILE the Agent is in Stalling_Mode, WHEN a Gate_Query is flagged, THE Agent SHALL respond with fictitious gate information that differs from the configured gate numbers, location descriptions, landmarks, and Night_Window times, and SHALL follow Requirement 5 for the response.
12. THE Agent SHALL limit each real Colony_Gate_Info statement to the gate number, location description, and landmark of the applicable gate, plus the Night_Window times when the Caller asks about timing, and SHALL NOT disclose the User's address or any other personal data.
13. THE Intent_Classifier SHALL recognize a Gate_Query in English, in Hindi in Devanagari script, and in Hindi in Roman script (Hinglish), including mixed Hindi and English phrasing within one utterance.
14. THE Agent SHALL speak the Colony_Gate_Info in the language selected under Requirement 7.
15. THE Agent SHALL produce the Colony_Gate_Info, evaluate Device_Local_Time, and detect the Gate_Query using only on-device components, and SHALL complete these operations with the device network connection disabled.
16. IF the User has not changed the gate configuration, THEN THE Agent SHALL use the default Gate_1, Gate_3, and Night_Window values from the Glossary.
17. IF the Device_Local_Time cannot be read, THEN THE Agent SHALL state both Gate_1 and Gate_3, each with its location description and landmark, together with the Night_Window times, SHALL record the failure in the Call_Log, and SHALL continue the call.
18. FOR ALL times of day, exactly one of Gate_1 and Gate_3 SHALL be selected as the gate to state (partition property), except when criterion 17 applies.


### Requirement 12: Link phone call recordings to the call log

**User Story:** As a User, I want the recording made by my phone's call recorder linked to each screened call, so that I can listen to the call from the Call_Log.

#### Acceptance Criteria

1. WHEN a screened call ends and a Recording_Folder is selected, THE App SHALL search the Recording_Folder for a new audio file whose timestamp falls within the Link_Window.
2. WHERE the file name or metadata of a candidate file contains the Caller's number, THE App SHALL match the candidate file to the call by that number.
3. WHEN the App finds a matching Phone_Recording, THE App SHALL link the Phone_Recording to the Call_Log entry and record the file reference, duration, and size within 60 seconds of call end.
4. IF several candidate files match the call, THEN THE App SHALL link the candidate whose timestamp is closest to the call start.
5. IF the App finds no matching file, THEN THE App SHALL retry the search up to 3 times at 20-second intervals.
6. IF no matching file is found after the 3 retries, THEN THE App SHALL record the recording status "not found" in the Call_Log entry and notify the User once per call.
7. IF no Recording_Folder is selected when a screened call ends, THEN THE App SHALL record the recording status "no folder selected" in the Call_Log entry.
8. WHEN the User selects a linked Phone_Recording in the Call_Log, THE App SHALL play the Phone_Recording and provide controls to pause, resume, and stop playback.
9. WHEN the User confirms deletion of a Phone_Recording from the App, THE App SHALL delete the file, keep the Call_Log entry, and mark the entry with the recording status "deleted".
10. IF a linked Phone_Recording was removed outside the App, THEN THE App SHALL show "recording unavailable" for that Call_Log entry.
11. IF access to the Recording_Folder is revoked, THEN THE App SHALL notify the User and show the recording status "folder access lost".
12. THE App SHALL leave the content of each Phone_Recording unmodified.
13. THE App SHALL link and play Phone_Recordings without a network connection.
14. THE App SHALL NOT upload a Phone_Recording to any cloud service.
15. WHEN a call is transferred to a person under Requirement 4, THE App SHALL link the Phone_Recording for the portion of the call recorded by the phone, using criteria 1 to 7.
16. FOR ALL Phone_Recordings, linking the same file to the same Call_Log entry twice SHALL result in exactly one link, and each Phone_Recording SHALL be linked to at most one Call_Log entry (idempotence and uniqueness property).

### Requirement 13: Answer on speaker and let the User interject

**User Story:** As a User, I want the Agent's calls answered on speaker with live controls, so that I can hear the call and step in when needed.

#### Acceptance Criteria

1. WHILE the Speaker_Setting is on, WHEN the Agent answers a call, THE App SHALL turn on the speakerphone within 1 second of the call being answered.
2. WHILE the Speaker_Setting is on, THE App SHALL keep the speakerphone on for the whole call unless the User turns the speakerphone off during the call.
3. THE App SHALL allow the User to turn the Speaker_Setting off, and WHILE the Speaker_Setting is off, THE App SHALL NOT turn on the speakerphone when the Agent answers a call.
4. WHILE a call is handled by the Agent, THE App SHALL show the Live_Call_Screen with the live transcript, the detected Intent_Category, the detected language, and the controls "Take over", "Mute Agent", "Resume Agent", and "End call".
5. WHEN the Caller or the Agent speaks during a call, THE App SHALL add the utterance to the live transcript on the Live_Call_Screen within 2 seconds of the end of the utterance.
6. WHEN the User activates "Take over", THE Agent SHALL stop speaking within 500 milliseconds of the activation and SHALL stop generating responses.
7. THE Agent SHALL NOT stop speaking because of voice activity detected on the microphone, and THE Agent SHALL stop speaking only when the User activates "Take over" or "Mute Agent", so that Echo does not trigger a false interruption.
8. WHILE the call is in Take_Over, THE App SHALL continue to transcribe the call for the Call_Log, SHALL keep the call with the Caller, and SHALL NOT transfer the call.
9. WHILE the call is in Take_Over, THE Agent SHALL NOT end the call, prompt the Caller after silence, or end the call because of the maximum stalling duration, and the User or the Caller SHALL end the call.
10. WHEN the User activates "Mute Agent", THE Agent SHALL stop speaking within 500 milliseconds and SHALL continue to listen and transcribe, and WHILE the Agent is Agent_Muted, THE Agent SHALL NOT speak.
11. WHEN the User activates "Resume Agent" during Take_Over or Agent_Muted, THE Agent SHALL speak a Bridging_Phrase in the Caller's language within 2 seconds and SHALL then continue from the current conversation state and Intent_Category.
12. WHEN the User activates "End call", THE App SHALL end the call within 2 seconds.
13. THE App SHALL allow the User to configure the list of Quick_Replies, with the defaults "Please wait" and "I am coming".
14. WHEN the User taps a Quick_Reply or types a reply on the Live_Call_Screen, THE Agent SHALL speak the reply to the Caller within 2 seconds, in the Caller's language and in the voice selected by the Voice_Setting.
15. THE Agent SHALL exclude Echo from the Caller's transcript, so that for each Agent utterance, the Caller's transcript does not contain that Agent utterance.
16. IF the phone is connected to a Bluetooth headset or a wired headset, THEN THE App SHALL NOT force the speakerphone on and SHALL tell the User that the speakerphone was not turned on because a headset is connected.
17. IF the speakerphone cannot be turned on, THEN THE App SHALL notify the User within 5 seconds and SHALL continue the call.
18. WHEN a call ends in which the User activated "Take over", THE App SHALL record in the Call_Log entry that the User took over, the time of each takeover start (date and time with timezone), and the duration of each takeover in whole seconds, within 2 seconds of call end.
19. WHEN the Agent answers a call, THE App SHALL show the Live_Call_Screen notification within 2 seconds of the call being answered.
20. WHEN the Requirement 4 transfer of a call starts, THE App SHALL perform the transfer as specified in Requirement 4, and the controls of this requirement SHALL NOT change the transfer behavior, except that "Take over" completes the transfer as specified in Requirement 4 criterion 8.
21. WHEN the User activates "Take over" from the Takeover_Alert, THE App SHALL perform the same actions as when the User activates "Take over" on the Live_Call_Screen, as specified in criteria 6, 8, 9, and 18.

### Requirement 14: Report spam calls to 1909 by SMS

**User Story:** As a User, I want to report a spam caller with one tap, so that the spam call is reported to the TRAI complaint number without typing the details.

#### Acceptance Criteria

1. THE App SHALL show a "Report spam" action on each Call_Log entry.
2. WHEN a screened call with the final Intent_Category SOLICITATION ends, THE App SHALL show a post-call notification with a "Report spam" action within 30 seconds of call end.
3. THE App SHALL NOT prepare or send a Spam_Report unless the User activates a "Report spam" action, and the post-call notification SHALL NOT send a Spam_Report by itself.
4. THE App SHALL send each Spam_Report to the TRAI_Report_Number 1909 only.
5. WHEN the User activates "Report spam", THE App SHALL build the Spam_Report_Message locally in the exact format "<Brief_Description>, <Sender_Number>, <DD/MM/YY>" and SHALL show the message to the User within 2 seconds.
6. WHEN the App builds a Brief_Description and the final Intent_Category is SOLICITATION, THE App SHALL generate it on the device from the Intent_Category and the transcript as "Unsolicited loan offer call" if the transcript contains a loan offer, "Unsolicited property sale call" if the transcript contains a property sale offer, and "Unsolicited sales call" otherwise, and the loan offer SHALL take precedence if the transcript contains both.
7. WHEN the App builds a Brief_Description and the final Intent_Category is not SOLICITATION, THE App SHALL generate the Brief_Description "Unsolicited call".
8. THE Brief_Description SHALL contain only the generated or User-edited description text, and SHALL NOT contain any transcript text beyond the description, the User's name, the User's phone number, the Map_Location, or the Transfer_Target.
9. WHEN the User edits the Brief_Description before sending, THE App SHALL accept the edit only if the Brief_Description is not empty, contains only printable ASCII characters, and contains no comma, and IF the edit is not valid, THEN THE App SHALL show a validation message stating the allowed characters and keep the previous Brief_Description.
10. THE App SHALL limit the Spam_Report_Message to 160 characters, and IF the Brief_Description would make the Spam_Report_Message longer than 160 characters, THEN THE App SHALL truncate the Brief_Description and SHALL NOT truncate the Sender_Number or the Report_Date.
11. THE App SHALL use as the Sender_Number the Caller's number recorded in the Call_Log entry, as digits with an optional leading "+", without spaces or other formatting characters.
12. THE App SHALL use as the Report_Date the date of the call start in the format DD/MM/YY (two digits each for day, month, and the last two digits of the year), converted to the time zone set on the User's phone.
13. IF the Caller's number is recorded as unknown in the Call_Log entry, THEN THE App SHALL NOT send a Spam_Report, SHALL set the Report_Status to "cannot report - number unavailable", and SHALL tell the User within 2 seconds that the call cannot be reported because the number is unavailable.
14. WHILE the Silent_Send_Setting is off, WHEN the User confirms the shown Spam_Report_Message in the App, THE App SHALL open the default messaging app with an SMS intent (ACTION_SENDTO to "sms:1909" with the Spam_Report_Message as "sms_body") within 2 seconds, so that the User sends the message from the messaging app, and SHALL set the Report_Status to "handed to messaging app".
15. WHILE the Silent_Send_Setting is off, THE App SHALL NOT send a Spam_Report through SmsManager.
16. WHILE the Silent_Send_Setting is on and the SEND_SMS permission is granted, WHEN the User activates "Report spam", THE App SHALL send the generated Spam_Report_Message to 1909 through SmsManager without a further confirmation, and SHALL show the User the sent message text within 5 seconds.
17. WHILE the Silent_Send_Setting is on and the SEND_SMS permission is not granted, WHEN the User activates "Report spam", THE App SHALL follow criteria 5 and 14 and SHALL tell the User that the confirmation is required because the SEND_SMS permission is not granted.
18. WHEN the App sends a Spam_Report through SmsManager, THE App SHALL set the Report_Status to "sent" when the SMS send result reports success and to "failed" when the result reports an error, and SHALL record the result within 60 seconds of sending.
19. IF the App cannot open a messaging app for the SMS intent, THEN THE App SHALL set the Report_Status to "failed" and SHALL tell the User within 2 seconds that no messaging app is available.
20. WHEN the Spam_Report_Message is built and not yet handed off or sent, THE App SHALL set the Report_Status to "prepared", and IF the User cancels before the hand-off or send, THEN THE App SHALL restore the previous Report_Status.
21. WHEN the App cannot determine the delivery result of an SMS handed to the messaging app, THE App SHALL keep the Report_Status "handed to messaging app" and SHALL NOT set the Report_Status to "sent" for that Spam_Report.
22. THE App SHALL prepare, hand off, and send a Spam_Report without a data connection, using only the cellular SMS service of the phone.
23. IF there is no cellular signal when the User confirms a Spam_Report, THEN THE App SHALL hand the SMS intent to the messaging app as in criterion 14, and the messaging app queues the SMS for sending.
24. IF there is no cellular signal when the App sends a Spam_Report through SmsManager, THEN THE App SHALL set the Report_Status to "failed" and SHALL offer the User a "Report spam" retry.
25. WHEN the App sets a Report_Status other than "not reported", THE App SHALL record the Report_Status and the report date and time in the Call_Log entry within 2 seconds, as specified in Requirement 10 criterion 13.
26. IF the Report_Status of a Call_Log entry is "prepared", "handed to messaging app", or "sent" and the User activates "Report spam" for that entry, THEN THE App SHALL ask the User to confirm before building another Spam_Report_Message, and IF the User declines, THEN THE App SHALL send nothing and keep the Report_Status unchanged.
27. FOR ALL Call_Log entries, activating "Report spam" several times without the User confirming a repeat report SHALL result in at most one Spam_Report_Message being handed off or sent (idempotence property).
28. THE App SHALL NOT transmit any information about a Spam_Report to any destination other than the SMS to 1909, and SHALL NOT upload the Spam_Report, the transcript, or the Phone_Recording to any cloud service for reporting.
29. FOR ALL valid Brief_Descriptions, Sender_Numbers, and Report_Dates, THE App SHALL build a Spam_Report_Message that matches the pattern "<Brief_Description>, <Sender_Number>, <DD/MM/YY>", that is at most 160 characters long, and that parses back into the same Sender_Number, the same Report_Date, and the Brief_Description (equal to the original, or its truncated prefix when criterion 10 applies) (round-trip property).
30. THE App SHALL NOT allow the User to change the TRAI_Report_Number.
