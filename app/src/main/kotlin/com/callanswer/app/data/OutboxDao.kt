package com.callanswer.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction

@Dao
abstract class OutboxDao {
    @Insert
    abstract suspend fun enqueue(item: OutboxEntity): Long

    /** Oldest first; items stay until explicitly deleted after delivery. */
    @Query("SELECT * FROM outbox ORDER BY createdAtEpochMs ASC, id ASC")
    abstract suspend fun pending(): List<OutboxEntity>

    @Query("SELECT COUNT(*) FROM outbox")
    abstract suspend fun count(): Int

    @Query("UPDATE outbox SET attempts = attempts + 1 WHERE id = :id")
    abstract suspend fun recordAttempt(id: Long): Int

    @Query("DELETE FROM outbox WHERE id = :id")
    abstract suspend fun delete(id: Long): Int

    @Query("SELECT * FROM outbox WHERE kind = :kind ORDER BY createdAtEpochMs ASC, id ASC LIMIT 1")
    abstract suspend fun firstOfKind(kind: String): OutboxEntity?

    @Query("DELETE FROM outbox WHERE kind = :kind")
    abstract suspend fun deleteKind(kind: String): Int

    /** Keeps at most one row of [item]'s kind (there is one pending settings edit at a time). */
    @Transaction
    open suspend fun replaceKind(item: OutboxEntity): Long {
        deleteKind(item.kind)
        return enqueue(item)
    }
}
