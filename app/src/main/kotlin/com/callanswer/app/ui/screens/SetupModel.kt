package com.callanswer.app.ui.screens
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import com.callanswer.app.CallAnswerApp
import com.callanswer.app.auth.AuthState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
/**
 * State shared by onboarding and Home: which setup items are granted, which ones the User just
 * denied (for the 2 s banner of 9.3), and the one-time flags. Compose-observable, no framework ViewModel.
 */
class SetupModel(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("setup", Context.MODE_PRIVATE)
    private val auth = (app as CallAnswerApp).authRepository
    /** Signed in. A build without the account feature has nothing to sign in to, so the item counts as done. */
    private fun signedIn() = auth.state.value is AuthState.SignedIn || !auth.isConfigured
    var status by mutableStateOf(SetupStatus.read(app, signedIn = signedIn()))
        private set
    var onboardingDone by mutableStateOf(prefs.getBoolean(KEY_ONBOARDING_DONE, false))
        private set
    /** Items the User denied in a request during this session; each gets a banner immediately (9.3). */
    val deniedThisSession = mutableStateListOf<SetupItem>()
    /** Re-reads every grant. Called on each resume so changes made in system settings show up. */
    suspend fun refresh() {
        status = withContext(Dispatchers.IO) { SetupStatus.read(app, signedIn()) }
        deniedThisSession.removeAll { status.isGranted(it) }
    }
    fun recordDenied(item: SetupItem) {
        if (item !in deniedThisSession) deniedThisSession += item
    }
    fun finishOnboarding() {
        prefs.edit { putBoolean(KEY_ONBOARDING_DONE, true) }
        onboardingDone = true
    }
    private companion object {
        const val KEY_ONBOARDING_DONE = "onboarding_done"
    }
}
