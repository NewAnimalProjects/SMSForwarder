package dev.zaplatin.smsforwarder

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class BatteryMonitorWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val percent = readBatteryPercent()
        if (percent == null) {
            OperationLogger.w(TAG, "Unable to read battery percent")
            return Result.retry()
        }

        val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastNotified = prefs.getInt(KEY_LAST_NOTIFIED_THRESHOLD, RESET_THRESHOLD)

        if (percent >= RESET_THRESHOLD) {
            if (lastNotified != RESET_THRESHOLD) {
                prefs.edit()
                    .putInt(KEY_LAST_NOTIFIED_THRESHOLD, RESET_THRESHOLD)
                    .apply()
                OperationLogger.d(TAG, "Battery reset at ${percent}%")
            }
            return Result.success()
        }

        val thresholdsToNotify = THRESHOLDS.filter { threshold ->
            percent <= threshold && threshold < lastNotified
        }

        if (thresholdsToNotify.isEmpty()) {
            OperationLogger.d(TAG, "No battery alerts needed at ${percent}%")
            return Result.success()
        }

        for (threshold in thresholdsToNotify) {
            try {
                OperationLogger.d(TAG, "Battery ${percent}% crossed ${threshold}%")
                TelegramClient.sendBatteryLowToTelegram(percent)
                prefs.edit()
                    .putInt(KEY_LAST_NOTIFIED_THRESHOLD, threshold)
                    .apply()
            } catch (e: Exception) {
                OperationLogger.e(TAG, "Failed to send battery alert at ${percent}%", e)
                return Result.retry()
            }
        }

        return Result.success()
    }

    private fun readBatteryPercent(): Int? {
        val intent = applicationContext.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
        ) ?: return null
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null
        return (level * 100f / scale).toInt()
    }

    companion object {
        private const val PREFS_NAME = "battery_monitor_prefs"
        private const val KEY_LAST_NOTIFIED_THRESHOLD = "last_notified_threshold"
        private const val RESET_THRESHOLD = 51
        private val THRESHOLDS = listOf(50, 20, 15, 10, 5, 2)
        private const val TAG = "BatteryMonitorWorker"
    }
}
