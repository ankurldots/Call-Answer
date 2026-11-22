package com.callanswer.app.telecom

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.telecom.TelecomManager
import com.callanswer.app.BuildConfig
import com.callanswer.app.audio.CallAudioAccessibilityService
import com.callanswer.app.audio.InCallUiAutomator
import com.callanswer.core.engine.PathAResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Path A transfer: places a second call to the Transfer_Target, which holds the Caller, and merges the calls.
 * Only called when `FrozenSettings.pathAEnabled` is true (the S6 build flag [BuildConfig.PATH_A_SUPPORTED] is on,
 * a Transfer_Target is configured and the accessibility service is enabled).
 */
interface TransferController {
    suspend fun connect(target: String): PathAResult
}

/** Telephony operations used by [PathATransferController]. */
interface TransferPhone {
    /** Places a cellular voice call to [target]. Throws when the platform refuses. */
    fun placeCall(target: String)

    /** Ends the current (second) call. Returns true when the platform accepted it. */
    fun endCall(): Boolean
}

/** In-call screen buttons used by [PathATransferController]. */
interface TransferInCallUi {
    fun isMergeEnabled(): Boolean
    fun clickMerge(): Boolean
    fun clickSwapOrResume(): Boolean
}

/**
 * `connect(target)`: `placeCall`, wait up to [noAnswerMs] (30 s) for Merge to be enabled, press Merge and return
 * MERGED. On timeout, or when placing the call, pressing Merge or any step throws or fails, the second call is
 * ended, Swap/Resume is pressed to resume the held call, and NO_ANSWER_30S (timeout) or FAILED is returned.
 */
class PathATransferController(
    private val phone: TransferPhone,
    private val ui: () -> TransferInCallUi?,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) : TransferController {

    override suspend fun connect(target: String): PathAResult {
        val started = clock()
        try {
            phone.placeCall(target)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Nothing was placed, so there is no second call to end; the held-call state is unchanged.
            return PathAResult.FAILED
        }
        return try {
            var enabled = false
            while (true) {
                if (ui()?.isMergeEnabled() == true) { enabled = true; break }
                if (clock() - started >= NO_ANSWER_MS) break
                pause(POLL_MS)
            }
            when {
                !enabled -> abort(PathAResult.NO_ANSWER_30S)
                ui()?.clickMerge() == true -> PathAResult.MERGED
                else -> abort(PathAResult.FAILED)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            abort(PathAResult.FAILED)
        }
    }

    /** Ends the second call, resumes the held call, and returns [result] (FAILED when the resume click failed). */
    private suspend fun abort(result: PathAResult): PathAResult {
        try { phone.endCall() } catch (e: Exception) { if (e is CancellationException) throw e }
        var resumed = false
        for (attempt in 1..RESUME_ATTEMPTS) {
            pause(RESUME_DELAY_MS)
            resumed = try { ui()?.clickSwapOrResume() == true } catch (e: Exception) {
                if (e is CancellationException) throw e
                false
            }
            if (resumed) break
        }
        return if (resumed) result else PathAResult.FAILED
    }

    companion object {
        const val NO_ANSWER_MS = 30_000L
        const val POLL_MS = 500L
        const val RESUME_DELAY_MS = 1_000L
        const val RESUME_ATTEMPTS = 3
    }
}

/** Real telephony: `TelecomManager.placeCall` with a `tel:` URI (cellular voice network only, no VoIP account). */
class TelecomTransferPhone(context: Context) : TransferPhone {
    private val app = context.applicationContext

    @SuppressLint("MissingPermission")
    override fun placeCall(target: String) {
        check(app.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
            "CALL_PHONE not granted"
        }
        val tm = checkNotNull(app.getSystemService(TelecomManager::class.java)) { "no TelecomManager" }
        tm.placeCall(Uri.fromParts("tel", target, null), null)
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    override fun endCall(): Boolean {
        if (app.checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) != PackageManager.PERMISSION_GRANTED) return false
        return app.getSystemService(TelecomManager::class.java)?.endCall() == true
    }
}

/** Adapts the accessibility service's [InCallUiAutomator]; null while the service is not enabled. */
private class AccessibilityInCallUi(private val automator: InCallUiAutomator) : TransferInCallUi {
    override fun isMergeEnabled() = automator.isMergeEnabled()
    override fun clickMerge() = automator.clickMerge()
    override fun clickSwapOrResume() = automator.clickSwapOrResume()
}

object TransferControllers {
    /** Builds the Path A controller, or null when the S6 build flag is off. */
    fun create(context: Context): TransferController? {
        if (!BuildConfig.PATH_A_SUPPORTED) return null
        return PathATransferController(
            phone = TelecomTransferPhone(context),
            ui = { CallAudioAccessibilityService.instance.value?.uiAutomator?.let(::AccessibilityInCallUi) },
        )
    }
}
