# Polly TTS API

Cognito email/password sign-in -> temporary AWS credentials -> SigV4-signed `POST /prod/tts` on an
API Gateway HTTP API (IAM auth) -> Lambda -> Amazon Polly (Kajal, neural). Each Cognito identity may
synthesize `DailyCharLimit` characters per UTC day (DynamoDB counter); beyond that the API returns 429.

## Deploy
```
cd lambda/polly_tts
sam build && sam deploy --guided --profile ldots --region ap-south-1
# optional: --parameter-overrides AdminCreateUsersOnly=true DailyCharLimit=10000
python3 -m unittest test_handler
```

## Connect the app
Put the stack outputs in `~/.gradle/gradle.properties` (public identifiers, not secrets):
```
callanswer.aws.region=ap-south-1
callanswer.cognito.userPoolId=<UserPoolId>
callanswer.cognito.clientId=<UserPoolClientId>
callanswer.cognito.identityPoolId=<IdentityPoolId>
callanswer.tts.apiUrl=<ApiUrl>
```
With these blank, the Account screen says sign-in is not set up and the app behaves as before.

## How the app uses it
Saving Delivery steps while signed in fetches audio for every sentence (English and Hindi) and stores it in
`filesDir/polly-audio`. During a call the voice chain tries that cache first, then Piper, then the platform TTS,
so calls never wait on the network. Only the female voice (Kajal) is cached.
