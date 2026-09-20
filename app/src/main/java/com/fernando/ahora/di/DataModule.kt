package com.fernando.ahora.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import com.fernando.ahora.core.time.ClockTicker
import com.fernando.ahora.core.time.SystemClockTicker
import com.fernando.ahora.core.time.SystemTimeProvider
import com.fernando.ahora.core.time.TimeProvider
import com.fernando.ahora.data.TaskRepositoryImpl
import com.fernando.ahora.data.local.AhoraDatabase
import com.fernando.ahora.data.local.RoomReminderStore
import com.fernando.ahora.data.local.TaskDao
import com.fernando.ahora.data.prefs.DataStoreAppPreferences
import com.fernando.ahora.domain.AppPreferences
import com.fernando.ahora.domain.ReminderPermissionSource
import com.fernando.ahora.domain.ReminderStore
import com.fernando.ahora.domain.TaskRepository
import com.fernando.ahora.reminders.ReminderPermissions
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal abstract class BindingsModule {
    @Binds abstract fun taskRepository(impl: TaskRepositoryImpl): TaskRepository
    @Binds abstract fun reminderStore(impl: RoomReminderStore): ReminderStore
    @Binds abstract fun timeProvider(impl: SystemTimeProvider): TimeProvider
    @Binds abstract fun clockTicker(impl: SystemClockTicker): ClockTicker
    @Binds abstract fun appPreferences(impl: DataStoreAppPreferences): AppPreferences
    @Binds abstract fun permissionSource(impl: ReminderPermissions): ReminderPermissionSource
}

@Module
@InstallIn(SingletonComponent::class)
internal object DatabaseModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): AhoraDatabase =
        Room.databaseBuilder(context, AhoraDatabase::class.java, AhoraDatabase.NAME).build()

    @Provides
    fun taskDao(db: AhoraDatabase): TaskDao = db.taskDao()

    @Provides
    @Singleton
    fun preferencesStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            // A corrupt file falls back to defaults instead of crashing the launch (ARCHITECTURE §7).
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            produceFile = { context.preferencesDataStoreFile("ahora_prefs") },
        )
}
