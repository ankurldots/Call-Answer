package com.callanswer.app.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert

/** The last settings document received from the server, with its Settings_Version. One row, [id] = 1. */
@Entity(tableName = "settings_cache")
data class SettingsCacheEntity(
    @PrimaryKey val id: Int = SINGLE_ROW,
    val version: Long,
    /** The `settings` object of the server document, as JSON. */
    val json: String,
    val updatedAtEpochMs: Long,
) {
    companion object { const val SINGLE_ROW = 1 }
}

@Dao
interface SettingsCacheDao {
    @Query("SELECT * FROM settings_cache WHERE id = 1")
    suspend fun get(): SettingsCacheEntity?

    @Upsert
    suspend fun put(row: SettingsCacheEntity)

    @Query("DELETE FROM settings_cache")
    suspend fun clear(): Int
}
