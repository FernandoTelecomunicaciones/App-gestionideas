package com.fernando.ahora.di

import com.fernando.ahora.domain.ReminderSync
import com.fernando.ahora.reminders.AlarmManagerScheduler
import com.fernando.ahora.reminders.AndroidReminderNotifier
import com.fernando.ahora.reminders.ReminderNotifier
import com.fernando.ahora.reminders.ReminderReconciler
import com.fernando.ahora.reminders.ReminderScheduler
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
abstract class ReminderBindings {
    @Binds abstract fun reminderSync(impl: ReminderReconciler): ReminderSync
    @Binds abstract fun scheduler(impl: AlarmManagerScheduler): ReminderScheduler
    @Binds abstract fun notifier(impl: AndroidReminderNotifier): ReminderNotifier
}

@Module
@InstallIn(SingletonComponent::class)
object AppScopeModule {
    @Provides
    @Singleton
    @ApplicationScope
    fun applicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
