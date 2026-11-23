package com.callanswer.app.ui.screens
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Warning
import androidx.compose.ui.graphics.vector.ImageVector
/** One illustration-style icon per setup step (material-icons-core only, no extended set). */
internal fun SetupItem.icon(): ImageVector = when (this) {
    SetupItem.NOTIFICATIONS -> Icons.Filled.Notifications
    SetupItem.FULL_SCREEN_INTENT -> Icons.Filled.Warning
    SetupItem.SEND_SMS -> Icons.Filled.Send
}
