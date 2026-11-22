package com.callanswer.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [CallLogEntity::class, OutboxEntity::class, SettingsCacheEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class CallAnswerDatabase : RoomDatabase() {
    abstract fun callLogDao(): CallLogDao
    abstract fun outboxDao(): OutboxDao
    abstract fun settingsCacheDao(): SettingsCacheDao

    companion object {
        /** Version 2: settings cache, pending settings edit columns on the outbox, cloud fields of the call log. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `outbox` ADD COLUMN `baseVersion` INTEGER")
                db.execSQL("ALTER TABLE `outbox` ADD COLUMN `changedFields` TEXT")
                db.execSQL("ALTER TABLE `outbox` ADD COLUMN `baseValues` TEXT")
                db.execSQL("ALTER TABLE `call_log` ADD COLUMN `cloudExtras` TEXT")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `settings_cache` (`id` INTEGER NOT NULL, `version` INTEGER NOT NULL, " +
                        "`json` TEXT NOT NULL, `updatedAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                )
            }
        }

        fun create(context: Context): CallAnswerDatabase =
            Room.databaseBuilder(context.applicationContext, CallAnswerDatabase::class.java, "call_answer.db")
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
