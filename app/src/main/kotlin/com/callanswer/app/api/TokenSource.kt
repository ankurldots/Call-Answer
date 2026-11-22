package com.callanswer.app.api

import com.callanswer.app.auth.AuthRepository

/** Supplies the Cognito ID token for `Authorization: Bearer` and for the first WebSocket message. */
fun interface TokenSource {
    /** Throws [com.callanswer.app.auth.AuthException] when the session is gone. [forceRefresh] skips the cached token. */
    suspend fun idToken(forceRefresh: Boolean): String
}

fun AuthRepository.asTokenSource() = TokenSource { force -> idToken(force) }
