package com.callanswer.app

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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.callanswer.app.ui.screens.AppNavGraph
import com.callanswer.app.ui.screens.SetupModel
import com.callanswer.app.ui.theme.AppTheme

/** Launcher activity: onboarding on first run, Home afterwards. */
class MainActivity : ComponentActivity() {
    private lateinit var model: SetupModel
    private var resumeTick by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        model = SetupModel(this)
        setContent {
            AppTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Box(Modifier.safeDrawingPadding()) { AppNavGraph(model, resumeTick) }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeTick++
    }
}
