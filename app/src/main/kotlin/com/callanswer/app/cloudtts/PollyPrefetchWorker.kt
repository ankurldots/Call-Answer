package com.callanswer.app.cloudtts
import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.callanswer.app.CallAnswerApp
import com.callanswer.app.auth.AuthState
import com.callanswer.app.translate.MlKitStepTranslator
import com.callanswer.core.model.VoiceGender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
/**
 * Background refresh of the Polly audio. Runs when the network is up; retries with backoff on network or server
 * errors. Stops without retrying when signed out, at the daily limit (the next settings change or app start tries
 * again), or when the voice is male (Kajal is female).
 */
class PollyPrefetchWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as CallAnswerApp
        if (!app.authRepository.isConfigured || app.authRepository.state.value !is AuthState.SignedIn) return Result.success()
        val settings = app.settingsStore.current()
        if (settings.voiceGender != VoiceGender.FEMALE) return Result.success()
        val result = app.pollyPrefetcher.refresh(settings, MlKitStepTranslator())
        return when (result.stoppedBy) {
            PollyException.Kind.NETWORK, PollyException.Kind.SERVER ->
                if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
            else -> Result.success()
        }
    }
    companion object {
        private const val NAME = "polly-prefetch"
        private const val MAX_ATTEMPTS = 5
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<PollyPrefetchWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            // REPLACE: a newer settings change supersedes a run still waiting or in progress.
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.REPLACE, request)
        }
        /**
         * Enqueues a refresh at app start, and again whenever the spoken sentences, the voice or the sign-in state
         * change (debounced so typing in a form does not start a run per keystroke).
         */
        @OptIn(FlowPreview::class)
        fun watch(app: CallAnswerApp, scope: CoroutineScope) {
            scope.launch {
                combine(app.settingsStore.settings, app.authRepository.state) { s, auth ->
                    Triple(SpokenTextCatalog.sentences(s), s.voiceGender, auth is AuthState.SignedIn)
                }
                    .distinctUntilChanged()
                    .debounce(2_000)
                    .collect { (_, gender, signedIn) ->
                        if (signedIn && gender == VoiceGender.FEMALE) enqueue(app)
                    }
            }
        }
    }
}
