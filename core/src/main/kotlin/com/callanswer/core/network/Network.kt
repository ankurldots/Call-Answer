package com.callanswer.core.network

import com.callanswer.core.model.NetworkQuality
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/** One HTTPS probe result. rttMs == null: timed out or failed. */
data class ProbeSample(val atMs: Long, val rttMs: Long?)

object NetworkClassifier {
    const val DEGRADED_RTT_MS = 1500L
    private const val FAILED_SHARE_LIMIT = 0.10

    fun classify(samples: List<ProbeSample>, nowMs: Long, windowMs: Long = 2000): NetworkQuality {
        val inWindow = samples.filter { it.atMs in (nowMs - windowMs)..nowMs }
        val successes = inWindow.mapNotNull { it.rttMs }
        if (successes.isEmpty()) return NetworkQuality.UNAVAILABLE
        val failed = inWindow.size - successes.size
        val worst = successes.max()
        val failedShare = failed.toDouble() / inWindow.size
        return if (worst > DEGRADED_RTT_MS || failedShare > FAILED_SHARE_LIMIT) {
            NetworkQuality.DEGRADED
        } else {
            NetworkQuality.AVAILABLE
        }
    }
}

enum class Source { LOCAL, CLOUD, LOCAL_AFTER_TIMEOUT, LOCAL_AFTER_ERROR }

data class Routed<T>(val value: T, val source: Source)

/** Cloud deadline: min(1500, 2400 - elapsed since end of speech), never negative. */
fun cloudDeadlineMs(elapsedSinceEndOfSpeechMs: Long): Long =
    maxOf(0L, minOf(1500L, 2400L - elapsedSinceEndOfSpeechMs))

class ProviderRouter(private val quality: () -> NetworkQuality) {
    suspend fun <T> cloudOrLocal(
        deadlineMs: Long,
        cloud: (suspend () -> T)?,
        local: suspend () -> T,
    ): Routed<T> {
        // Quality is read once per request.
        val q = quality()
        if (q != NetworkQuality.AVAILABLE || cloud == null) {
            return Routed(local(), Source.LOCAL)
        }
        return coroutineScope {
            val localJob = async { local() }
            var failure = Source.LOCAL_AFTER_TIMEOUT
            val cloudValue: Box<T>? = try {
                withTimeoutOrNull(deadlineMs) { Box(cloud()) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = Source.LOCAL_AFTER_ERROR
                null
            }
            if (cloudValue != null) {
                localJob.cancel()
                Routed(cloudValue.value, Source.CLOUD)
            } else {
                Routed(localJob.await(), failure)
            }
        }
    }

    private class Box<T>(val value: T)
}
