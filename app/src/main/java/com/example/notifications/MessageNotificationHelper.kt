package com.example.notifications

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.data.model.MessageThreadSummary

object MessageNotificationHelper {
    const val CHANNEL_ID = "eksi_direct_messages"
    const val EXTRA_MESSAGE_THREAD_URL = "extra_message_thread_url"
    const val EXTRA_OPEN_MESSAGES_TAB = "extra_open_messages_tab"
    private const val SUMMARY_NOTIFICATION_ID = 41000

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Özel Mesajlar",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Ekşi Sözlük yazarlarından yeni özel mesaj geldiğinde bildirim gönderir."
            }
            manager.createNotificationChannel(channel)
        }
    }

    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    @SuppressLint("MissingPermission")
    fun showNewMessageNotifications(context: Context, threads: List<MessageThreadSummary>) {
        if (threads.isEmpty() || !hasNotificationPermission(context)) return
        ensureChannel(context)

        val notificationManager = NotificationManagerCompat.from(context)
        if (!notificationManager.areNotificationsEnabled()) return

        if (threads.size <= 3) {
            threads.forEach { thread ->
                val intent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra(EXTRA_MESSAGE_THREAD_URL, thread.threadUrl)
                    putExtra(EXTRA_OPEN_MESSAGES_TAB, true)
                }
                val requestCode = thread.threadUrl.hashCode()
                val pendingIntent = PendingIntent.getActivity(
                    context,
                    requestCode,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val body = thread.preview.ifBlank { "Yeni özel mesajınızı okumak için dokunun." }
                val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_notification_mail)
                    .setContentTitle("@${thread.participantNick} mesaj gönderdi")
                    .setContentText(body)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                    .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setAutoCancel(true)
                    .setContentIntent(pendingIntent)
                    .build()

                notificationManager.notify(notificationIdForThread(thread.threadUrl), notification)
            }
        } else {
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_OPEN_MESSAGES_TAB, true)
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                SUMMARY_NOTIFICATION_ID,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val senders = threads.map { "@${it.participantNick}" }.distinct().take(4).joinToString(", ")
            val summaryText = "$senders ve diğerlerinden okunmamış mesajlarınız var."
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_mail)
                .setContentTitle("${threads.size} yeni özel mesaj")
                .setContentText(summaryText)
                .setStyle(NotificationCompat.BigTextStyle().bigText(summaryText))
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()

            notificationManager.notify(SUMMARY_NOTIFICATION_ID, notification)
        }
    }

    fun cancelThreadNotification(context: Context, threadUrl: String) {
        NotificationManagerCompat.from(context).cancel(notificationIdForThread(threadUrl))
    }

    fun cancelAllMessageNotifications(context: Context) {
        NotificationManagerCompat.from(context).cancelAll()
    }

    private fun notificationIdForThread(threadUrl: String): Int =
        41001 + (threadUrl.hashCode() and 0x0FFF_FFFF)
}
