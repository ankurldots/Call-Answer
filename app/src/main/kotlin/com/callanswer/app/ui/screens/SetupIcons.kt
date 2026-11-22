package com.callanswer.app.ui.screens
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.ui.graphics.vector.ImageVector
/** One illustration-style icon per setup step (material-icons-core only, no extended set). */
internal fun SetupItem.icon(): ImageVector = when (this) {
    SetupItem.CALL_SCREENING_ROLE -> Icons.Filled.Lock
    SetupItem.CONTACTS -> Icons.Filled.Person
    SetupItem.PHONE -> Icons.Filled.Phone
    SetupItem.CALL_PHONE -> Icons.Filled.Call
    SetupItem.MICROPHONE -> Icons.Filled.Star
    SetupItem.NOTIFICATIONS -> Icons.Filled.Notifications
    SetupItem.SMS -> Icons.Filled.Send
    SetupItem.ACCESSIBILITY -> Icons.Filled.Info
    SetupItem.FULL_SCREEN_INTENT -> Icons.Filled.Warning
    SetupItem.BATTERY -> Icons.Filled.Settings
    SetupItem.RECORDING_FOLDER -> Icons.Filled.Home
    SetupItem.AUTO_RECORD -> Icons.Filled.CheckCircle
}
