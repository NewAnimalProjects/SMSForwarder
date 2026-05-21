package dev.zaplatin.smsforwarder

import android.app.Application

class SmsForwarderApp : Application() {

    override fun onCreate() {
        super.onCreate()
        BatteryMonitorScheduler.schedule(this)
    }
}
