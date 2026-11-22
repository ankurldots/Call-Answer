package com.callanswer.core.tenant

import com.callanswer.core.model.Settings

/** Result of a versioned settings update. */
sealed interface SettingsUpdate {
    data class Saved(val document: Settings, val version: Long) : SettingsUpdate
    data class Conflict(val currentDocument: Settings, val currentVersion: Long) : SettingsUpdate
    data class Invalid(val field: String) : SettingsUpdate
}
