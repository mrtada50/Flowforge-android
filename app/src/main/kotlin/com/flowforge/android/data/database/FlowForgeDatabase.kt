package com.flowforge.android.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        WorkflowEntity::class,
        WorkflowNodeEntity::class,
        WorkflowConnectionEntity::class,
        ExecutionSessionEntity::class,
        ExecutionResultEntity::class,
        DatasetEntity::class,
        ProxyProfileEntity::class,
        AppSettingEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class FlowForgeDatabase : RoomDatabase() {
    abstract fun workflowDao(): WorkflowDao
    abstract fun executionDao(): ExecutionDao
    abstract fun settingsDao(): SettingsDao
    abstract fun datasetDao(): DatasetDao
    abstract fun proxyDao(): ProxyDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE workflows ADD COLUMN variablesJson TEXT NOT NULL DEFAULT '{}'")
                db.execSQL("ALTER TABLE workflows ADD COLUMN settingsJson TEXT NOT NULL DEFAULT '{}'")
                db.execSQL("ALTER TABLE execution_results ADD COLUMN preview TEXT")
            }
        }

        fun build(context: Context): FlowForgeDatabase =
            Room.databaseBuilder(context.applicationContext, FlowForgeDatabase::class.java, "flowforge.db")
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
