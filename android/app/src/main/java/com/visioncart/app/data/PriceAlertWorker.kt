package com.visioncart.app.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.visioncart.app.MainActivity
import java.util.Calendar
import java.util.concurrent.TimeUnit

class PriceAlertWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "PriceAlertWorker"
        private const val WORK_NAME = "price_alert_check"
        private const val CHANNEL_ID = "price_alerts"
        private const val NOTIFIED_PREFS = "price_alert_notifications"

        // 后端检查时间: 8:00, 14:00, 20:00
        // Android 延后 1 分钟执行，等待后端完成
        private val CHECK_HOURS = intArrayOf(8, 14, 20)
        private const val CHECK_MINUTE = 1

        fun schedule(context: Context) {
            val delayMs = calculateDelayToNextCheck()
            val hours = delayMs / 3600_000
            val mins = (delayMs % 3600_000) / 60_000
            Log.i(TAG, "Next check in ${hours}h${mins}m (${delayMs}ms)")

            val request = OneTimeWorkRequestBuilder<PriceAlertWorker>()
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }

        fun notifyTriggeredAlertNow(context: Context, alert: PriceAlertCard) {
            val triggeredAt = alert.triggeredAt ?: return
            val appContext = context.applicationContext
            if (wasNotified(appContext, alert.productId, triggeredAt)) return
            val currentPrice = alert.currentPrice ?: return
            val targetPrice = alert.targetPrice
            val title = alert.title ?: "商品"
            val message = if (targetPrice > 0 && currentPrice <= targetPrice) {
                "$title 已降至 ¥${"%.0f".format(currentPrice)}，低于目标价 ¥${"%.0f".format(targetPrice)}！"
            } else {
                "$title 价格变动: ¥${"%.0f".format(currentPrice)}"
            }
            PriceAlertNotifier(appContext).notify(alert.productId, message)
            markNotified(appContext, alert.productId, triggeredAt)
        }

        private fun calculateDelayToNextCheck(): Long {
            val now = Calendar.getInstance()
            val currentHour = now.get(Calendar.HOUR_OF_DAY)
            val currentMinute = now.get(Calendar.MINUTE)

            for (hour in CHECK_HOURS) {
                if (currentHour < hour || (currentHour == hour && currentMinute < CHECK_MINUTE)) {
                    return delayToToday(hour, CHECK_MINUTE)
                }
            }
            // 所有今天的检查时间已过，计算到明天第一个
            return delayToTomorrow(CHECK_HOURS[0], CHECK_MINUTE)
        }

        private fun delayToToday(hour: Int, minute: Int): Long {
            val target = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            return target.timeInMillis - System.currentTimeMillis()
        }

        private fun delayToTomorrow(hour: Int, minute: Int): Long {
            val target = Calendar.getInstance().apply {
                add(Calendar.DAY_OF_YEAR, 1)
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            return target.timeInMillis - System.currentTimeMillis()
        }

        private fun wasNotified(context: Context, productId: String, triggeredAt: String): Boolean {
            val key = notificationKey(productId, triggeredAt)
            return context
                .getSharedPreferences(NOTIFIED_PREFS, Context.MODE_PRIVATE)
                .getBoolean(key, false)
        }

        private fun markNotified(context: Context, productId: String, triggeredAt: String) {
            val key = notificationKey(productId, triggeredAt)
            context
                .getSharedPreferences(NOTIFIED_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(key, true)
                .apply()
        }

        private fun notificationKey(productId: String, triggeredAt: String): String {
            return "$productId:$triggeredAt"
        }
    }

    override suspend fun doWork(): Result {
        Log.i(TAG, "Running price alert check")
        return try {
            ensureAuthLoaded()
            val api = ApiClient.api
            val response = api.getPriceAlerts()
            if (response.code != 200 || response.data == null) {
                Log.w(TAG, "Failed to fetch alerts: ${response.message}")
                schedule(applicationContext)
                return Result.success()
            }

            val alerts = response.data
            var notifiedCount = 0

            for (alert in alerts) {
                val triggeredAt = alert.triggeredAt ?: continue
                if (wasNotified(applicationContext, alert.productId, triggeredAt)) continue

                val title = alert.title ?: "商品"
                val currentPrice = alert.currentPrice ?: continue
                val targetPrice = alert.targetPrice
                val favoritePrice = alert.favoritePrice ?: 0.0

                val message = when {
                    targetPrice > 0 && currentPrice <= targetPrice ->
                        "$title 已降至 ¥${"%.0f".format(currentPrice)}，低于目标价 ¥${"%.0f".format(targetPrice)}！"
                    favoritePrice > 0 -> {
                        val drop = (favoritePrice - currentPrice) / favoritePrice * 100
                        "🔥 $title 降价 ${"%.0f".format(drop)}%，¥${"%.0f".format(favoritePrice)}→¥${"%.0f".format(currentPrice)}"
                    }
                    else -> "$title 价格变动: ¥${"%.0f".format(currentPrice)}"
                }

                PriceAlertNotifier(applicationContext).notify(alert.productId, message)
                markNotified(applicationContext, alert.productId, triggeredAt)
                notifiedCount++
            }

            Log.i(TAG, "Check complete: $notifiedCount notifications for ${alerts.size} alerts")
            schedule(applicationContext)
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Price alert check failed", e)
            schedule(applicationContext)
            Result.success()
        }
    }

    private suspend fun ensureAuthLoaded() {
        if (!ApiClient.authToken.isNullOrBlank()) return
        ApiClient.appContext = applicationContext
        ApiClient.authToken = TokenManager.getToken(applicationContext)
        ApiClient.refreshTokenValue = TokenManager.getRefreshToken(applicationContext)
        ApiClient.currentUserId = TokenManager.getUserId(applicationContext)
    }

}

private class PriceAlertNotifier(private val context: Context) {
    fun notify(productId: String, message: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "价格提醒",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "商品降价提醒"
            }
            nm.createNotificationChannel(channel)
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("navigate", "favorites")
        }
        val pendingIntent = PendingIntent.getActivity(
            context, productId.hashCode(),
            intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_notification_overlay)
            .setContentTitle("价格提醒")
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        nm.notify(productId.hashCode(), notification)
    }

    private companion object {
        private const val CHANNEL_ID = "price_alerts"
    }
}
