package dev.zaplatin.smsforwarder

/** Phone state read by bot commands. Android implementation: [AndroidDeviceStatusProvider]. */
interface DeviceStatusProvider {
    fun battery(): BatteryStatus?
    fun sims(): SimsResult

    /** Total stored SMS and how many are not sent yet. */
    suspend fun smsCounts(): Pair<Int, Int>

    /** Newest first. */
    suspend fun lastSms(limit: Int): List<SmsMessageEntity>
    fun serviceStartedAt(): Long?
    fun now(): Long

    /** False before the first unlock after a reboot: SMS history (Room) is not readable then. */
    fun isUserUnlocked(): Boolean
}

data class BatteryStatus(
    val percent: Int,
    val isCharging: Boolean,
    val plugType: PlugType?,
)

enum class PlugType(val label: String) {
    AC("сеть"),
    USB("USB"),
    WIRELESS("беспроводная"),
}

data class SimInfo(val label: String, val number: String?)

sealed interface SimsResult {
    data class Available(val sims: List<SimInfo>) : SimsResult
    object PermissionMissing : SimsResult
}
