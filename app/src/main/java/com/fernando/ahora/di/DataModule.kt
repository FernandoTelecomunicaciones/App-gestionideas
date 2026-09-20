package com.fernando.ahora.di

import android.content.Context
import androidx.room.Room
import com.fernando.ahora.core.time.SystemTimeProvider
import com.fernando.ahora.core.time.TimeProvider
import com.fernando.ahora.data.TaskRepositoryImpl
import com.fernando.ahora.data.local.AhoraDatabase
import com.fernando.ahora.data.local.RoomReminderStore
import com.fernando.ahora.data.local.TaskDao
import com.fernando.ahora.domain.ReminderStore
import com.fernando.ahora.domain.TaskRepository
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
}
