package com.callanswer.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * An item that could not be completed yet (failed notification, map SMS result, unwritten entry, pending settings edit).
 *
 * A pending settings edit ([OutboxKinds.SETTINGS_EDIT]) uses the three extra columns: [baseVersion] is the
 * Settings_Version of the server document the edit started from, [changedFields] is a JSON object with only the
 * locally changed fields, and [baseValues] is a JSON object with the server values of those fields at [baseVersion]
 * (the "base" side of the three-way merge). [payload] stays empty for that kind.
 */
@Entity(tableName = "outbox")
data class OutboxEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,
    val payload: String,
    val createdAtEpochMs: Long,
    val attempts: Int = 0,
    val baseVersion: Long? = null,
    val changedFields: String? = null,
    val baseValues: String? = null,
)

object OutboxKinds {
    const val SETTINGS_EDIT = "settings_edit"

    /** A Map_SMS result that could not be reported yet. Payload: `{"callId":"...","status":"SENT"}`. */
    const val MAP_SMS_RESULT = "map_sms_result"
}
