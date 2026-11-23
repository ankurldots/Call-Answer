package com.callanswer.agent.exotel

import com.callanswer.agent.Clock
import com.callanswer.agent.SystemClock
import java.util.concurrent.ConcurrentHashMap
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest

/**
 * [SecretSource] over AWS Secrets Manager. Values (and misses) are cached for [cacheMillis] so a
 * rotation takes effect within that time. Secret values are never logged.
 */
class SecretsManagerSource(
    private val fetch: (String) -> String?,
    private val clock: Clock = SystemClock,
    private val cacheMillis: Long = CACHE_MILLIS,
) : SecretSource {
    private data class Entry(val value: String?, val expiresAt: Long)

    private val cache = ConcurrentHashMap<String, Entry>()

    constructor(client: SecretsManagerClient, clock: Clock = SystemClock) : this(
        { name ->
            try {
                client.getSecretValue(GetSecretValueRequest.builder().secretId(name).build()).secretString()
            } catch (_: Exception) {
                null
            }
        },
        clock,
    )

    override fun get(name: String): String? {
        val now = clock.nowMillis()
        cache[name]?.takeIf { now < it.expiresAt }?.let { return it.value }
        val value = fetch(name)
        cache[name] = Entry(value, now + cacheMillis)
        return value
    }

    companion object {
        const val CACHE_MILLIS = 30_000L
    }
}
