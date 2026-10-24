package dev.zaplatin.smsforwarder

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Low-battery alert logic shared by [BatteryMonitorWorker] (periodic fallback) and
 * [ForwarderService] (real-time). State lives in SharedPreferences; the mutex keeps both
 * callers from sending the same alert twice.
 */
object BatteryAlerts {
    private const val PREFS_NAME = "battery_monitor_prefs"
    private const val KEY_LAST_NOTIFIED_THRESHOLD = "last_notified_threshold"
    internal const val RESET_THRESHOLD = 51
    private val THRESHOLDS = listOf(50, 20, 15, 10, 5, 2)
    private const val TAG = "BatteryAlerts"

    private val mutex = Mutex()

    /** Returns false when an alert could not be sent and the check should be retried. */
    suspend fun process(context: Context, percent: Int): Boolean = mutex.withLock {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastNotified = prefs.getInt(KEY_LAST_NOTIFIED_THRESHOLD, RESET_THRESHOLD)

        if (percent >= RESET_THRESHOLD) {
            if (lastNotified != RESET_THRESHOLD) {
                prefs.edit()
                    .putInt(KEY_LAST_NOTIFIED_THRESHOLD, RESET_THRESHOLD)
                    .apply()
                OperationLogger.d(TAG, "Battery reset at ${percent}%")
            }
            return@withLock true
        }

        val thresholdsToNotify = thresholdsToNotify(percent, lastNotified)
        if (thresholdsToNotify.isEmpty()) {
            OperationLogger.d(TAG, "No battery alerts needed at ${percent}%")
            return@withLock true
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
                return@withLock false
            }
        }
        true
    }

    internal fun thresholdsToNotify(percent: Int, lastNotified: Int): List<Int> =
        THRESHOLDS.filter { threshold -> percent <= threshold && threshold < lastNotified }

    fun readBatteryPercent(context: Context): Int? {
        val intent = context.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
        ) ?: return null
        return percentFrom(intent)
    }

    fun percentFrom(intent: Intent): Int? {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null
        return (level * 100f / scale).toInt()
    }
}
