package dev.zaplatin.smsforwarder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restarts [ForwarderService] after a reboot or an app update. */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        OperationLogger.d(TAG, "Received $action, starting service")
        ForwarderService.start(context)
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
