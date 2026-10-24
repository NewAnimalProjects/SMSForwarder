package dev.zaplatin.smsforwarder

import android.app.Application

class SmsForwarderApp : Application() {

    override fun onCreate() {
        super.onCreate()
        BatteryMonitorScheduler.schedule(this)
        // Restores the service when the process is restarted (e.g. by an incoming SMS).
        // Allowed from the background once the app is exempt from battery optimization.
        ForwarderService.start(this)
    }
}
