package com.callanswer.app
import android.app.Application
import android.util.Log
import com.callanswer.app.auth.AuthConfig
import com.callanswer.app.auth.AuthRepository
import com.callanswer.app.auth.AuthState
import com.callanswer.app.auth.KeystoreTokenStore
import com.callanswer.app.data.CallAnswerDatabase
import com.callanswer.app.data.SettingsStore
import com.callanswer.app.legacy.LegacyA11yCleanup
import com.callanswer.app.report.AndroidSmsIntentLauncher
import com.callanswer.app.report.SpamReporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
/** Application entry. The app is the companion app of the cloud Agent; it answers no calls and enables no accessibility service. */
class CallAnswerApp : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val database: CallAnswerDatabase by lazy { CallAnswerDatabase.create(this) }
    val settingsStore: SettingsStore by lazy { SettingsStore.create(this) }
    val authConfig: AuthConfig by lazy { AuthConfig.fromBuild() }
    val authRepository: AuthRepository by lazy { AuthRepository(authConfig, KeystoreTokenStore(this)) }
    val spamReporter: SpamReporter by lazy {
        SpamReporter.create(
            context = this,
            dao = database.callLogDao(),
            settings = settingsStore,
            launcher = AndroidSmsIntentLauncher(this),
        )
    }
    override fun onCreate() {
        super.onCreate()
        // Devices that ran the archived on-device build may still have its accessibility service enabled.
        LegacyA11yCleanup.run(this, "app start")
        Log.i("CallAnswer", "Auth configured=${authConfig.isConfigured}")
        if (authConfig.isConfigured) {
            appScope.launch { authRepository.state.collect { Log.i("CallAnswer", "Auth state: ${if (it is AuthState.SignedIn) "SignedIn" else "SignedOut"}") } }
        }
    }
}
