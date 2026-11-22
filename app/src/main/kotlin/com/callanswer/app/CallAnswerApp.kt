package com.callanswer.app

import android.app.Application
import android.util.Log
import com.callanswer.app.auth.AuthState
import com.callanswer.app.auth.AuthConfig
import com.callanswer.app.auth.AuthRepository
import com.callanswer.app.auth.KeystoreTokenStore
import com.callanswer.app.cloudtts.PollyAudioCache
import com.callanswer.app.cloudtts.PollyClient
import com.callanswer.app.cloudtts.PollyPrefetchWorker
import com.callanswer.app.cloudtts.PollyPrefetcher
import com.callanswer.app.data.CallAnswerDatabase
import com.callanswer.app.data.SettingsStore
import com.callanswer.app.report.SpamReporter
import com.callanswer.app.sms.AndroidCellServiceState
import com.callanswer.app.sms.AndroidSmsGateway
import com.callanswer.app.sms.AndroidSmsIntentLauncher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/** Application entry. Registers the [SpamReporter] for silent send results at app start (task 31.3). */
class CallAnswerApp : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database: CallAnswerDatabase by lazy { CallAnswerDatabase.create(this) }

    val settingsStore: SettingsStore by lazy { SettingsStore.create(this) }

    val authConfig: AuthConfig by lazy { AuthConfig.fromBuild() }
    val authRepository: AuthRepository by lazy { AuthRepository(authConfig, KeystoreTokenStore(this)) }
    val pollyCache: PollyAudioCache by lazy { PollyAudioCache(File(filesDir, "polly-audio")) }
    val pollyPrefetcher: PollyPrefetcher by lazy { PollyPrefetcher(PollyClient(authConfig, authRepository), pollyCache) }
    val spamReporter: SpamReporter by lazy {
        SpamReporter.create(
            context = this,
            dao = database.callLogDao(),
            settings = settingsStore,
            launcher = AndroidSmsIntentLauncher(this),
            gateway = AndroidSmsGateway(this),
            serviceState = AndroidCellServiceState(this),
            scope = appScope,
        )
    }

    override fun onCreate() {
        super.onCreate()
        com.callanswer.app.service.NotificationCenter(this).createChannels()
        spamReporter.register()
        // Accessibility must be on only during a call: switch it off if a crash or reboot left it on.
        com.callanswer.app.a11y.A11yToggle.startupCheck(this, "app start")
        Log.i("CallAnswer", "Auth configured=${authConfig.isConfigured}")
        if (authConfig.isConfigured) {
            appScope.launch { authRepository.state.collect { Log.i("CallAnswer", "Auth state: ${if (it is AuthState.SignedIn) "SignedIn" else "SignedOut"}") } }
            PollyPrefetchWorker.watch(this, appScope)
        }
    }
}
