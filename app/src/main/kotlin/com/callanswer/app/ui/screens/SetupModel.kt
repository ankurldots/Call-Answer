package com.callanswer.app.ui.screens

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import com.callanswer.app.CallAnswerApp
import com.callanswer.app.auth.AuthState
import com.callanswer.app.data.SettingsStore
import com.callanswer.app.recording.RecordingFolderRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * State shared by onboarding and Home: which setup items are granted, which ones the User just
 * denied (for the 2 s banner of 9.3), and the one-time flags. Compose-observable, no framework ViewModel.
 */
class SetupModel(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("setup", Context.MODE_PRIVATE)
    private val folders = RecordingFolderRepository(app)
    private val settings = SettingsStore.create(app)

    private val auth = (app as CallAnswerApp).authRepository

    /** Signed in. A build without the account feature has nothing to sign in to, so the item counts as done. */
    private fun signedIn() = auth.state.value is AuthState.SignedIn || !auth.isConfigured

    var status by mutableStateOf(SetupStatus.read(app, folderGranted = false, autoRecordAcknowledged = false, signedIn = signedIn()))
        private set
    var screeningEnabled by mutableStateOf(true)
        private set
    var onboardingDone by mutableStateOf(prefs.getBoolean(KEY_ONBOARDING_DONE, false))
        private set

    /** Items the User denied in a request during this session; each gets a banner immediately (9.3). */
    val deniedThisSession = mutableStateListOf<SetupItem>()

    /** Re-reads every grant. Called on each resume so changes made in system settings show up. */
    suspend fun refresh() {
        val (folderOk, enabled) = withContext(Dispatchers.IO) {
            val s = settings.current()
            val uri = s.recordingFolderUri?.let(Uri::parse)
            (uri != null && folders.hasAccess(uri)) to s.screeningEnabled
        }
        screeningEnabled = enabled
        status = SetupStatus.read(app, folderOk, prefs.getBoolean(KEY_AUTORECORD_ACK, false), signedIn())
        deniedThisSession.removeAll { status.isGranted(it) }
    }

    /** Persists the screening on/off setting (same store as the settings screen) and re-reads state. */
    suspend fun setScreeningEnabled(enabled: Boolean) {
        withContext(Dispatchers.IO) { settings.update(settings.current().copy(screeningEnabled = enabled)) }
        refresh()
    }

    fun recordDenied(item: SetupItem) {
        if (item !in deniedThisSession) deniedThisSession += item
    }

    /** Persists the picked folder (read access only, no broad storage permission) and stores its Uri (9.11). */
    suspend fun saveFolder(uri: Uri): Boolean {
        val persisted = withContext(Dispatchers.IO) { folders.persistAccess(uri) }
        if (!persisted) return false
        withContext(Dispatchers.IO) {
            settings.update(settings.current().copy(recordingFolderUri = uri.toString()))
        }
        refresh()
        return true
    }

    fun acknowledgeAutoRecord() {
        prefs.edit { putBoolean(KEY_AUTORECORD_ACK, true) }
        status = status.copy(
            granted = status.granted + (SetupItem.AUTO_RECORD to true),
            autoRecordAcknowledged = true,
        )
    }

    fun finishOnboarding() {
        prefs.edit { putBoolean(KEY_ONBOARDING_DONE, true) }
        onboardingDone = true
    }

    fun folderContract() = folders.selectFolderContract()

    private companion object {
        const val KEY_ONBOARDING_DONE = "onboarding_done"
        const val KEY_AUTORECORD_ACK = "autorecord_ack"
    }
}
