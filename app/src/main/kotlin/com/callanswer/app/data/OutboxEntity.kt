package com.callanswer.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** An item that could not be completed yet (failed notification, map SMS result, unwritten entry). */
@Entity(tableName = "outbox")
data class OutboxEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,
    val payload: String,
    val createdAtEpochMs: Long,
    val attempts: Int = 0,
)
