package com.fernando.ahora.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Version 1. Schemas are exported to app/schemas and every bump must ship a tested migration;
 * `fallbackToDestructiveMigration` is forbidden (NFR-04, D-23).
 */
@Database(entities = [TaskEntity::class], version = 1, exportSchema = true)
internal abstract class AhoraDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao

    companion object {
        const val NAME = "ahora.db"
    }
}
