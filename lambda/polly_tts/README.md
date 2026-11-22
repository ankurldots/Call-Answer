# Polly TTS API

Cognito email/password sign-in -> temporary AWS credentials -> SigV4-signed `POST /prod/tts` on an
API Gateway HTTP API (IAM auth) -> Lambda -> Amazon Polly (Kajal, neural). Each Cognito identity may
synthesize `DailyCharLimit` characters per UTC day (DynamoDB counter); beyond that the API returns 429.

## Deploy
```
cd lambda/polly_tts
sam build && sam deploy --guided --profile ldots --region ap-south-1
# self sign-up is off by default; add users with the commands below.
# optional: --parameter-overrides AdminCreateUsersOnly=false DailyCharLimit=10000
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

## Add a user (no self registration)
```
POOL=<UserPoolId>; EMAIL=someone@example.com
aws cognito-idp admin-create-user --user-pool-id $POOL --username $EMAIL \
  --user-attributes Name=email,Value=$EMAIL Name=email_verified,Value=true --message-action SUPPRESS \
  --profile ldots --region ap-south-1
aws cognito-idp admin-set-user-password --user-pool-id $POOL --username $EMAIL \
  --password '<10+ chars, upper, lower, digit>' --permanent --profile ldots --region ap-south-1
```
