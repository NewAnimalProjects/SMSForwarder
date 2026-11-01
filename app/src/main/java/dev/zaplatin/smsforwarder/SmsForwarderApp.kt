package dev.zaplatin.smsforwarder

import android.app.Application

class SmsForwarderApp : Application() {

    override fun onCreate() {
        super.onCreate()
        if (DirectBoot.isUserUnlocked(this)) {
            BatteryMonitorScheduler.schedule(this)
        } else {
            // WorkManager's database is in credential-protected storage; BootReceiver schedules
            // the work on BOOT_COMPLETED, which follows the first unlock.
            OperationLogger.d(TAG, "Started before first unlock, WorkManager deferred")
        }
        // Restores the service when the process is restarted (e.g. by an incoming SMS).
        // Allowed from the background once the app is exempt from battery optimization.
        ForwarderService.start(this)
    }

    private companion object {
        const val TAG = "SmsForwarderApp"
    }
}
