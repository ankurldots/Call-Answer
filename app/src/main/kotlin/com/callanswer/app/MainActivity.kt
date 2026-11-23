package com.callanswer.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.callanswer.app.api.takeOver
import com.callanswer.app.push.AndroidPushNotifier
import com.callanswer.app.push.LiveCallRequest
import com.callanswer.app.ui.screens.AppNavGraph
import com.callanswer.app.ui.screens.SetupModel
import com.callanswer.app.ui.theme.AppTheme
import kotlinx.coroutines.launch

/** Launcher activity: onboarding on first run, Home afterwards. */
class MainActivity : ComponentActivity() {
    private lateinit var model: SetupModel
    private var resumeTick by mutableIntStateOf(0)
    private var liveRequest by mutableStateOf<LiveCallRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        model = SetupModel(this)
        handleLiveIntent(intent)
        setContent {
            AppTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Box(Modifier.safeDrawingPadding()) { AppNavGraph(model, resumeTick, liveRequest) { liveRequest = null } }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLiveIntent(intent)
    }

    /** The Takeover_Alert and the live-call notification open the live call screen; "Take over" also calls the server (Requirement 19.8). */
    private fun handleLiveIntent(intent: Intent?) {
        val request = LiveCallRequest.from(intent) ?: return
        liveRequest = request
        val app = application as CallAnswerApp
        getSystemService(android.app.NotificationManager::class.java)?.cancel(AndroidPushNotifier.takeoverId(request.callId))
        if (request.takeOver) {
            // Consume the action so that a recreated activity does not take over twice.
            intent?.action = LiveCallRequest.ACTION_OPEN
            app.appScope.launch { runCatching { app.companionApi.takeOver(request.callId) } }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeTick++
    }
}
