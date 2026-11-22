package com.callanswer.app.auth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
class AuthRepositoryTest {
    private class MemoryStore : TokenStore {
        var session: StoredSession? = null
        override fun load() = session
        override fun save(session: StoredSession) { this.session = session }
        override fun clear() { session = null }
    }
    private val config = AuthConfig("ap-south-1", "ap-south-1_pool", "client", "ap-south-1:identity", "https://api.example.com/prod/tts")
    private var now = 1_000_000L
    private val calls = mutableListOf<Pair<String, String>>() // target, body
    private val failures = mutableMapOf<String, Pair<Int, String>>()
    private val transport = Transport { _, headers, body ->
        val target = headers.getValue("X-Amz-Target").substringAfterLast('.')
        calls += target to body
        failures[target]?.let { return@Transport it }
        200 to when (target) {
            "InitiateAuth" -> if ("REFRESH_TOKEN_AUTH" in body) """{"AuthenticationResult":{"IdToken":"id2","ExpiresIn":3600}}"""
            else """{"AuthenticationResult":{"IdToken":"id1","RefreshToken":"refresh1","ExpiresIn":3600}}"""
            "GetId" -> """{"IdentityId":"ap-south-1:abc"}"""
            "GetCredentialsForIdentity" ->
                """{"Credentials":{"AccessKeyId":"AK","SecretKey":"SK","SessionToken":"ST","Expiration":${now + 3600}.0}}"""
            else -> "{}"
        }
    }
    private val store = MemoryStore()
    private fun repo() = AuthRepository(config, store, CognitoApi(config, transport), Dispatchers.Unconfined) { now }
    @Test fun signInStoresRefreshTokenAndNotPassword() = runBlocking {
        val r = repo()
        r.signIn(" a@b.com ", "Passw0rdPassw0rd")
        assertEquals(AuthState.SignedIn("a@b.com"), r.state.value)
        assertEquals(StoredSession("a@b.com", "refresh1"), store.session)
        val body = Json.parseToJsonElement(calls.single().second).jsonObject
        assertEquals("USER_PASSWORD_AUTH", body["AuthFlow"]!!.jsonPrimitive.content)
    }
    @Test fun wrongPasswordMapsToError() = runBlocking {
        failures["InitiateAuth"] = 400 to """{"__type":"NotAuthorizedException","message":"x"}"""
        try { repo().signIn("a@b.com", "x"); fail() } catch (e: AuthException) { assertEquals(AuthError.WRONG_CREDENTIALS, e.error) }
        assertNull(store.session)
    }
    @Test fun credentialsAreCachedUntilNearExpiry() = runBlocking {
        val r = repo(); r.signIn("a@b.com", "p")
        val first = r.credentials(); val second = r.credentials()
        assertEquals("ST", first.sessionToken)
        assertEquals(1, calls.count { it.first == "GetCredentialsForIdentity" })
        assertTrue(first === second)
        now += 3600 - 30 // inside the refresh margin
        r.credentials()
        assertEquals(2, calls.count { it.first == "GetCredentialsForIdentity" })
        assertEquals(1, calls.count { it.first == "GetId" })
    }
    @Test fun refreshesIdTokenWhenExpired() = runBlocking {
        val r = repo(); r.signIn("a@b.com", "p")
        now += 4000
        r.credentials()
        assertTrue(calls.any { it.first == "InitiateAuth" && "REFRESH_TOKEN_AUTH" in it.second })
    }
    @Test fun revokedRefreshTokenSignsOut() = runBlocking {
        val r = repo(); r.signIn("a@b.com", "p")
        now += 4000
        failures["InitiateAuth"] = 400 to """{"__type":"NotAuthorizedException"}"""
        try { r.credentials(); fail() } catch (e: AuthException) { assertEquals(AuthError.NOT_SIGNED_IN, e.error) }
        assertEquals(AuthState.SignedOut, r.state.value)
        assertNull(store.session)
    }
    @Test fun signOutClearsSessionAndRevokes() = runBlocking {
        val r = repo(); r.signIn("a@b.com", "p")
        r.signOut()
        assertEquals(AuthState.SignedOut, r.state.value)
        assertNull(store.session)
        assertTrue(calls.any { it.first == "RevokeToken" })
    }
    @Test fun networkFailureIsReported() = runBlocking {
        val bad = AuthRepository(config, store, CognitoApi(config) { _, _, _ -> throw java.io.IOException() }, Dispatchers.Unconfined)
        try { bad.signIn("a@b.com", "p"); fail() } catch (e: AuthException) { assertEquals(AuthError.NETWORK, e.error) }
    }
    @Test fun unconfiguredBuildRefusesCredentials() = runBlocking {
        val off = AuthRepository(AuthConfig("", "", "", "", ""), store, CognitoApi(config, transport), Dispatchers.Unconfined)
        try { off.credentials(); fail() } catch (e: AuthException) { assertEquals(AuthError.NOT_CONFIGURED, e.error) }
    }
}
