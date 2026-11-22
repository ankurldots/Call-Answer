package com.callanswer.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [CallLogEntity::class, OutboxEntity::class], version = 1, exportSchema = true)
abstract class CallAnswerDatabase : RoomDatabase() {
    abstract fun callLogDao(): CallLogDao
    abstract fun outboxDao(): OutboxDao

    companion object {
        fun create(context: Context): CallAnswerDatabase =
            Room.databaseBuilder(context.applicationContext, CallAnswerDatabase::class.java, "call_answer.db").build()
    }
}
