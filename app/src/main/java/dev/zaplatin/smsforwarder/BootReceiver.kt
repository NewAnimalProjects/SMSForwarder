package dev.zaplatin.smsforwarder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Restarts [ForwarderService] after a reboot or an app update. LOCKED_BOOT_COMPLETED arrives
 * before the first unlock (Direct Boot); BOOT_COMPLETED only after it.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        when (val action = intent?.action) {
            Intent.ACTION_LOCKED_BOOT_COMPLETED -> {
                OperationLogger.d(TAG, "Received $action, starting service before unlock")
                ForwarderService.start(context)
            }
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                OperationLogger.d(TAG, "Received $action, starting service")
                // The process may have started before unlock and skipped this in SmsForwarderApp.
                BatteryMonitorScheduler.schedule(context)
                ForwarderService.start(context)
            }
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
