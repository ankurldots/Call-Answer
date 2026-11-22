package com.callanswer.app

import android.app.Application
import com.callanswer.app.auth.AuthConfig
import com.callanswer.app.auth.AuthRepository
import com.callanswer.app.auth.KeystoreTokenStore
import com.callanswer.app.cloudtts.PollyAudioCache
import com.callanswer.app.cloudtts.PollyClient
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
        spamReporter.register()
    }
}
