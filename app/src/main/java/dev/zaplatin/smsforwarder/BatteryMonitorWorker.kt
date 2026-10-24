package dev.zaplatin.smsforwarder

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/** Periodic fallback for battery alerts in case [ForwarderService] is not running. */
class BatteryMonitorWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val percent = BatteryAlerts.readBatteryPercent(applicationContext)
        if (percent == null) {
            OperationLogger.w(TAG, "Unable to read battery percent")
            return Result.retry()
        }
        return if (BatteryAlerts.process(applicationContext, percent)) Result.success() else Result.retry()
    }

    companion object {
        private const val TAG = "BatteryMonitorWorker"
    }
}
