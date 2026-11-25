package com.example.notifications

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.EksiAppContainer
import com.example.EksiApplication
import kotlinx.coroutines.flow.first

class MessageCheckWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (!MessageNotificationHelper.hasNotificationPermission(applicationContext)) {
            return Result.success()
        }

        val repository = (applicationContext as? EksiApplication)?.container?.repository
            ?: EksiAppContainer(applicationContext).repository

        val prefs = repository.userPreferences.first()
        if (!prefs.messageNotificationsEnabled) {
            return Result.success()
        }

        val result = repository.fetchUnreadMessagesForNotification(ignoreSeenCache = false)
        result.onSuccess { newUnreadThreads ->
            if (newUnreadThreads.isNotEmpty()) {
                MessageNotificationHelper.showNewMessageNotifications(
                    context = applicationContext,
                    threads = newUnreadThreads
                )
            }
        }

        return Result.success()
    }
}
