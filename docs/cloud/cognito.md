# Cognito

## Task 0.6: `admins` group

- Region: `ap-south-1`, AWS profile `ldots`
- User pool: `ap-south-1_TKxhHQ1Bq` (name "Call answer"). It is the only pool with that name in the account. The repo has no reference to a pool id, so the pool was identified by name.
- Group `admins` did not exist (`list-groups` returned empty) and was created with `create-group`.
- Member added with `admin-add-user-to-group`: username `f123cd0a-…-31b391` (email `a…@gmail.com`). It was the only user in the pool (status CONFIRMED).
- Verified with `admin-list-groups-for-user`: the user is in `admins`.
- Pool settings were not modified (task 0.7 handles sign-up policy).

## 0.7 Disable self sign-up

- Pool `ap-south-1_TKxhHQ1Bq`. App client `UserPoolClient-DkbJfxHmv3dM` (no client secret; flows: USER_PASSWORD_AUTH, REFRESH_TOKEN_AUTH).
- Backup of `describe-user-pool` taken before any change: `/tmp/cognito-pool-backup-before.json` (outside the repo).
- Finding: `AdminCreateUserConfig.AllowAdminCreateUserOnly` was **already `true`** (pool last modified 2026-11-22 13:33 +05:30, before this task). No `update-user-pool` call was made, so nothing was reset or changed and there is no diff to report.
- The pool is managed by CloudFormation stack `call-answer-polly-tts`. Any manual change would drift from the template; the template should keep `AllowAdminCreateUserOnly: true`.
- Live check: `aws cognito-idp sign-up` with the app client id and `test+selfsignup@example.invalid` returned `NotAuthorizedException: SignUp is not permitted for this user pool`. No user was created.
- Android app: `auth/AuthRepository.kt` still has `signUp`/`confirmSignUp`, and `AccountScreen.kt` has a `Mode.SIGN_UP` flow, but it is gated by `private const val SELF_SIGN_UP = false` (line 32). App code was not changed.
