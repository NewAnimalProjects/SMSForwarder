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
        val action = intent?.action ?: return
        if (action != Intent.ACTION_MY_PACKAGE_REPLACED && !isBootAction(action)) return
        OperationLogger.d(TAG, "Received $action, starting service")
        if (action != Intent.ACTION_LOCKED_BOOT_COMPLETED) {
            // The process may have started before unlock and skipped this in SmsForwarderApp.
            BatteryMonitorScheduler.schedule(context)
        }
        ForwarderService.start(context, afterBoot = isBootAction(action))
    }

    companion object {
        private const val TAG = "BootReceiver"

        /** Both arrive on every boot (LOCKED_ first); the service dedupes the notice. */
        internal fun isBootAction(action: String): Boolean =
            action == Intent.ACTION_LOCKED_BOOT_COMPLETED || action == Intent.ACTION_BOOT_COMPLETED
    }
}
