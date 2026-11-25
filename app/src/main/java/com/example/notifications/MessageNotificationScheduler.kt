package com.example.notifications

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object MessageNotificationScheduler {
    private const val UNIQUE_PERIODIC_WORK = "eksi_direct_message_poll"
    private const val UNIQUE_IMMEDIATE_WORK = "eksi_direct_message_check_now"

    fun syncPeriodicWork(context: Context, enabled: Boolean) {
        val workManager = WorkManager.getInstance(context)
        if (!enabled) {
            workManager.cancelUniqueWork(UNIQUE_PERIODIC_WORK)
            return
        }

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = PeriodicWorkRequestBuilder<MessageCheckWorker>(
            15,
            TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .build()

        workManager.enqueueUniquePeriodicWork(
            UNIQUE_PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    fun triggerImmediateCheck(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = OneTimeWorkRequestBuilder<MessageCheckWorker>()
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_IMMEDIATE_WORK,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }
}
