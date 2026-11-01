package dev.zaplatin.smsforwarder

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object BatteryMonitorScheduler {
    private const val UNIQUE_WORK_NAME = "battery_monitor_work"

    /** Requires an unlocked user: WorkManager keeps its database in credential-protected storage. */
    fun schedule(context: Context) {
        try {
            enqueue(context)
            OperationLogger.d(TAG, "Battery monitor scheduled")
        } catch (e: IllegalStateException) {
            OperationLogger.e(TAG, "Unable to schedule battery monitor", e)
        }
    }

    private fun enqueue(context: Context) {
        val request = PeriodicWorkRequestBuilder<BatteryMonitorWorker>(
            15,
            TimeUnit.MINUTES,
        )
            .addTag(UNIQUE_WORK_NAME)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    private const val TAG = "BatteryMonitorScheduler"
}
