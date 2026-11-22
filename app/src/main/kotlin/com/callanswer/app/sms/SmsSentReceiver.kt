package com.callanswer.app.sms

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/** Receives final outcomes of a silent send. SpamReporter registers itself here. */
interface SilentSendResultSink {
    fun onSendResult(entryId: String, attemptId: String, success: Boolean)
    fun onSendTimeout(entryId: String, attemptId: String)
}

object SilentSendResults {
    @Volatile
    var sink: SilentSendResultSink? = null
}

/** Folds per-part result codes into one outcome: the first failure fails the send, all successes give success. */
object SentPartAggregator {
    /** Only [Activity.RESULT_OK] is success; every other code is a failure (14.18). */
    fun isSuccess(resultCode: Int): Boolean = resultCode == Activity.RESULT_OK

    /** Outcome after [okParts] successes so far and a new part result, or null while parts are still pending. */
    fun outcome(resultCode: Int, okParts: Int, partCount: Int): Boolean? = when {
        !isSuccess(resultCode) -> false
        okParts + 1 >= partCount -> true
        else -> null
    }
}

class SmsSentReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val entryId = intent.getStringExtra(EXTRA_ENTRY_ID) ?: return
        val attemptId = intent.getStringExtra(EXTRA_ATTEMPT_ID) ?: return
        val partCount = intent.getIntExtra(EXTRA_PART_COUNT, 1)
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val done = prefs.getBoolean("$attemptId.done", false)
        if (done) return
        val ok = prefs.getInt("$attemptId.ok", 0)
        val outcome = SentPartAggregator.outcome(resultCode, ok, partCount)
        if (outcome == null) {
            prefs.edit().putInt("$attemptId.ok", ok + 1).apply()
            return
        }
        prefs.edit().putBoolean("$attemptId.done", true).apply()
        SilentSendTimeoutWorker.cancel(context, attemptId)
        SilentSendResults.sink?.onSendResult(entryId, attemptId, outcome)
    }

    companion object {
        const val EXTRA_ENTRY_ID = "entryId"
        const val EXTRA_ATTEMPT_ID = "attemptId"
        const val EXTRA_PART_INDEX = "partIndex"
        const val EXTRA_PART_COUNT = "partCount"
        private const val PREFS = "sms_sent_progress"

        /** One explicit, immutable sent PendingIntent per part; the request code is unique per attempt and part. */
        fun pendingIntents(context: Context, entryId: String, attemptId: String, partCount: Int): List<PendingIntent> =
            List(partCount) { index ->
                val intent = Intent(context, SmsSentReceiver::class.java)
                    .putExtra(EXTRA_ENTRY_ID, entryId)
                    .putExtra(EXTRA_ATTEMPT_ID, attemptId)
                    .putExtra(EXTRA_PART_INDEX, index)
                    .putExtra(EXTRA_PART_COUNT, partCount)
                PendingIntent.getBroadcast(
                    context, "$attemptId#$index".hashCode(), intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            }
    }
}

/** Raises SilentSendTimeout 60 s after a silent send when no result arrived (14.18). */
class SilentSendTimeoutWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val entryId = inputData.getString(KEY_ENTRY_ID) ?: return Result.failure()
        val attemptId = inputData.getString(KEY_ATTEMPT_ID) ?: return Result.failure()
        SilentSendResults.sink?.onSendTimeout(entryId, attemptId)
        return Result.success()
    }

    companion object {
        const val TIMEOUT_SEC = 60L
        private const val KEY_ENTRY_ID = "entryId"
        private const val KEY_ATTEMPT_ID = "attemptId"

        private fun workName(attemptId: String) = "silent-send-timeout-$attemptId"

        fun schedule(context: Context, entryId: String, attemptId: String) {
            val request = OneTimeWorkRequestBuilder<SilentSendTimeoutWorker>()
                .setInitialDelay(TIMEOUT_SEC, TimeUnit.SECONDS)
                .setInputData(workDataOf(KEY_ENTRY_ID to entryId, KEY_ATTEMPT_ID to attemptId))
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(workName(attemptId), ExistingWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context, attemptId: String) {
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(workName(attemptId))
        }
    }
}
