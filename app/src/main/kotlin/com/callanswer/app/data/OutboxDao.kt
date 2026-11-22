package com.callanswer.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface OutboxDao {
    @Insert
    suspend fun enqueue(item: OutboxEntity): Long

    /** Oldest first; items stay until explicitly deleted after delivery. */
    @Query("SELECT * FROM outbox ORDER BY createdAtEpochMs ASC, id ASC")
    suspend fun pending(): List<OutboxEntity>

    @Query("SELECT COUNT(*) FROM outbox")
    suspend fun count(): Int

    @Query("UPDATE outbox SET attempts = attempts + 1 WHERE id = :id")
    suspend fun recordAttempt(id: Long): Int

    @Query("DELETE FROM outbox WHERE id = :id")
    suspend fun delete(id: Long): Int
}
