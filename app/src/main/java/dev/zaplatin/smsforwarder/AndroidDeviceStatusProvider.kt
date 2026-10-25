package dev.zaplatin.smsforwarder

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

class AndroidDeviceStatusProvider(private val context: Context) : DeviceStatusProvider {

    private val dao get() = AppDatabase.getInstance(context).smsDao()

    override fun battery(): BatteryStatus? {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return null
        val percent = BatteryAlerts.percentFrom(intent) ?: return null
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        val plugType = when (intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)) {
            BatteryManager.BATTERY_PLUGGED_AC -> PlugType.AC
            BatteryManager.BATTERY_PLUGGED_USB -> PlugType.USB
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> PlugType.WIRELESS
            else -> null
        }
        return BatteryStatus(percent, isCharging, plugType)
    }

    override fun sims(): SimsResult =
        if (SimInfoReader.hasPhoneStatePermission(context)) {
            SimsResult.Available(SimInfoReader.read(context))
        } else {
            SimsResult.PermissionMissing
        }

    override suspend fun smsCounts(): Pair<Int, Int> = dao.countAll() to dao.countNotSent()

    override suspend fun lastSms(limit: Int): List<SmsMessageEntity> = dao.getLatest(limit)

    override fun serviceStartedAt(): Long? = ForwarderService.startedAt

    override fun now(): Long = System.currentTimeMillis()
}
