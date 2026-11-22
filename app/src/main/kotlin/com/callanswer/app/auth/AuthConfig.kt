package com.callanswer.app.auth
import com.callanswer.app.BuildConfig
/** Public identifiers of the deployed stack (from the SAM outputs). None of these is a secret. */
data class AuthConfig(
    val region: String,
    val userPoolId: String,
    val clientId: String,
    val identityPoolId: String,
    val apiUrl: String,
) {
    val isConfigured: Boolean
        get() = listOf(region, userPoolId, clientId, identityPoolId).all { it.isNotBlank() } &&
            apiUrl.startsWith("https://")
    /** Key of the user pool in the identity pool `Logins` map. */
    val loginsKey: String get() = "cognito-idp.$region.amazonaws.com/$userPoolId"
    companion object {
        fun fromBuild() = AuthConfig(
            BuildConfig.AWS_REGION, BuildConfig.COGNITO_USER_POOL_ID, BuildConfig.COGNITO_CLIENT_ID,
            BuildConfig.COGNITO_IDENTITY_POOL_ID, BuildConfig.TTS_API_URL,
        )
    }
}
