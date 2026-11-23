package com.callanswer.app
import android.app.Application
import android.util.Log
import com.callanswer.app.api.ApiConfig
import com.callanswer.app.api.CompanionApi
import com.callanswer.app.api.LiveSocket
import com.callanswer.app.api.alertAck
import com.callanswer.app.api.asTokenSource
import com.callanswer.app.api.registerDevice
import com.callanswer.app.auth.AuthConfig
import com.callanswer.app.auth.AuthRepository
import com.callanswer.app.auth.AuthState
import com.callanswer.app.auth.KeystoreTokenStore
import com.callanswer.app.data.CallAnswerDatabase
import com.callanswer.app.data.CallLogRepository
import com.callanswer.app.data.SettingsRepository
import com.callanswer.app.data.SettingsStore
import com.callanswer.app.legacy.LegacyA11yCleanup
import com.callanswer.app.report.AndroidSmsIntentLauncher
import com.callanswer.app.report.SpamReporter
import com.callanswer.app.sms.AndroidSubscriptionSource
import com.callanswer.app.sms.LocalSmsSettings
import com.callanswer.app.sms.MapSmsReporter
import com.callanswer.app.sms.MapSmsSender
import com.callanswer.app.sms.SmsManagerTransport
import com.callanswer.app.screening.RecentCallCache
import com.callanswer.app.screening.RingingAlert
import com.callanswer.app.screening.RepeatCallerMatcher
import com.callanswer.app.screening.ScreeningObserver
import com.callanswer.core.cloud.RingThroughPolicy
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
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
    val apiConfig: ApiConfig by lazy { ApiConfig() }
    val companionApi: CompanionApi by lazy { CompanionApi(apiConfig, authRepository.asTokenSource()) }
    val liveSocket: LiveSocket by lazy { LiveSocket(apiConfig, authRepository.asTokenSource()) }
    val settingsRepository: SettingsRepository by lazy {
        SettingsRepository(companionApi, database.settingsCacheDao(), database.outboxDao())
    }
    val callLogRepository: CallLogRepository by lazy { CallLogRepository(companionApi, database.callLogDao()) }
    val spamReporter: SpamReporter by lazy {
        SpamReporter.create(
            context = this,
            dao = database.callLogDao(),
            settings = settingsStore,
            launcher = AndroidSmsIntentLauncher(this),
            syncStatus = { id, status -> companionApi.reportStatus(id, status) },
        )
    }
    /** SMS settings that stay on this device: the SIM choice and the handled call ids. */
    val localSmsSettings: LocalSmsSettings by lazy { LocalSmsSettings(this) }
    val mapSmsReporter: MapSmsReporter by lazy {
        MapSmsReporter(post = { id, result -> companionApi.mapSmsResult(id, result) }, outbox = database.outboxDao())
    }
    /** Called by the push handler for a `send_map_sms` message (task 12.7): `mapSmsSender.onSendMapSms(callId, number, text)`. */
    val mapSmsSender: MapSmsSender by lazy {
        MapSmsSender(
            permissionGranted = { checkSelfPermission(android.Manifest.permission.SEND_SMS) == android.content.pm.PackageManager.PERMISSION_GRANTED },
            transport = SmsManagerTransport(this),
            subscriptions = AndroidSubscriptionSource(this),
            selectedSubscriptionId = { localSmsSettings.selectedSubscriptionId },
            claim = localSmsSettings::claim,
            sink = mapSmsReporter,
        )
    }
    val pushRegistration: com.callanswer.app.push.PushRegistration by lazy {
        com.callanswer.app.push.PushRegistration(this) { id, token -> companionApi.registerDevice(id, token) }
    }
    val pushNotifier: com.callanswer.app.push.AndroidPushNotifier by lazy { com.callanswer.app.push.AndroidPushNotifier(this) }
    /** Acts on the FCM data messages (task 12.7). The `recent_caller` hook is filled by task 12.26. */
    val pushHandler: com.callanswer.app.push.PushHandler by lazy {
        com.callanswer.app.push.PushHandler(
            notifier = pushNotifier,
            alertAck = { companionApi.alertAck(it) },
            sendMapSms = { callId, number, text -> mapSmsSender.onSendMapSms(callId, number, text) },
        )
    }
    /** Recent calls for the Screening_Observer (tasks 12.20, 12.22, 12.26). */
    val recentCallCache: RecentCallCache by lazy { RecentCallCache.create(this) }

    /** The Ringing_Alert notification for a Repeat_Caller (Requirement 33). */
    val ringingAlert: RingingAlert by lazy { RingingAlert(this) }

    /** Replaces the recent-call cache from the server at app start and when the call log opens (task 12.22). */
    val recentCallSync: com.callanswer.app.screening.RecentCallSync by lazy {
        com.callanswer.app.screening.RecentCallSync(companionApi, recentCallCache)
    }

    val screeningObserver: ScreeningObserver by lazy {
        ScreeningObserver(
            RepeatCallerMatcher(
                cache = recentCallCache,
                windowMinutes = {
                    runCatching { settingsRepository.effective() }.getOrNull()
                        ?.get("ringThroughWindowMinutes")?.jsonPrimitive?.intOrNull
                        ?: RingThroughPolicy.DEFAULT_WINDOW_MINUTES
                },
            ),
            alerter = ringingAlert,
            enabled = {
                runCatching { settingsRepository.effective() }.getOrNull()
                    ?.get("ringThroughEnabled")?.jsonPrimitive?.booleanOrNull
                    ?: true
            },
        )
    }

    override fun onCreate() {
        super.onCreate()
        // Devices that ran the archived on-device build may still have its accessibility service enabled.
        LegacyA11yCleanup.run(this, "app start")
        Log.i("CallAnswer", "Auth configured=${authConfig.isConfigured}")
        // Results that could not be reported before (offline) go out now.
        appScope.launch { runCatching { mapSmsReporter.flush() } }
        if (authConfig.isConfigured) {
            appScope.launch { runCatching { recentCallSync.sync() } }
            appScope.launch { authRepository.state.collect {
                Log.i("CallAnswer", "Auth state: ${if (it is AuthState.SignedIn) "SignedIn" else "SignedOut"}")
                // The FCM token is registered after every sign-in (and at app start while signed in).
                if (it is AuthState.SignedIn) runCatching { pushRegistration.registerCurrent() }
            } }
        }
    }
}
