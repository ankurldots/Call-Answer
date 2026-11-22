package com.callanswer.app.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.SystemClock
import com.callanswer.core.model.NetworkQuality
import com.callanswer.core.network.NetworkClassifier
import com.callanswer.core.network.ProbeSample
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.URL

/**
 * Probes connectivity with a small HTTPS request every 500 ms and exposes the classified quality.
 * Runs only while a call is in progress: call [start] at call start and [stop] at call end.
 * The probe carries no User data (plain GET to a generate_204 style endpoint).
 */
class NetworkMonitor(
    context: Context,
    private val probeUrl: () -> String = { DEFAULT_PROBE_URL },
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val connectivity =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _quality = MutableStateFlow(NetworkQuality.UNAVAILABLE)
    val quality: StateFlow<NetworkQuality> = _quality.asStateFlow()

    private val lock = Any()
    private val samples = ArrayDeque<ProbeSample>()
    private var job: Job? = null
    private var callback: ConnectivityManager.NetworkCallback? = null

    private val activeNetworkCount = java.util.concurrent.atomic.AtomicInteger(0)

    fun start(scope: CoroutineScope) {
        synchronized(lock) {
            if (job != null) return
            samples.clear()
            _quality.value = NetworkQuality.UNAVAILABLE
            registerCallback()
            job = scope.launch(io) {
                while (true) {
                    val startedAt = SystemClock.elapsedRealtime()
                    val rtt = probeOnce()
                    val now = SystemClock.elapsedRealtime()
                    record(ProbeSample(atMs = now, rttMs = rtt))
                    val spent = now - startedAt
                    delay((PROBE_INTERVAL_MS - spent).coerceAtLeast(0L))
                }
            }
        }
    }

    fun stop() {
        synchronized(lock) {
            job?.cancel()
            job = null
            callback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
            callback = null
            samples.clear()
        }
    }

    private fun record(sample: ProbeSample) {
        val q = synchronized(lock) {
            if (job == null) return
            samples.addLast(sample)
            prune(sample.atMs)
            NetworkClassifier.classify(samples.toList(), sample.atMs)
        }
        _quality.value = q
    }

    private fun prune(nowMs: Long) {
        while (samples.isNotEmpty() && samples.first().atMs < nowMs - WINDOW_MS) samples.removeFirst()
    }

    private fun registerCallback() {
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                activeNetworkCount.incrementAndGet()
            }

            override fun onLost(network: Network) {
                // Immediate loss: drop stale successes so the classifier reports UNAVAILABLE now.
                if (activeNetworkCount.decrementAndGet() <= 0) {
                    activeNetworkCount.set(0)
                    synchronized(lock) { samples.clear() }
                    _quality.value = NetworkQuality.UNAVAILABLE
                }
            }
        }
        callback = cb
        runCatching { connectivity.registerDefaultNetworkCallback(cb) }
    }

    /** Returns the round-trip time in ms, or null on failure or timeout. */
    private suspend fun probeOnce(): Long? = withContext(io) {
        val t0 = SystemClock.elapsedRealtime()
        val ok = withTimeoutOrNull(PROBE_TIMEOUT_MS) {
            runCatching {
                val conn = URL(probeUrl()).openConnection() as HttpURLConnection
                try {
                    conn.connectTimeout = PROBE_TIMEOUT_MS.toInt()
                    conn.readTimeout = PROBE_TIMEOUT_MS.toInt()
                    conn.requestMethod = "GET"
                    conn.useCaches = false
                    conn.responseCode in 200..399
                } finally {
                    conn.disconnect()
                }
            }.getOrDefault(false)
        } ?: false
        if (ok) SystemClock.elapsedRealtime() - t0 else null
    }

    companion object {
        const val DEFAULT_PROBE_URL = "https://connectivitycheck.gstatic.com/generate_204"
        const val PROBE_INTERVAL_MS = 500L
        const val PROBE_TIMEOUT_MS = 2000L
        private const val WINDOW_MS = 2000L
    }
}
