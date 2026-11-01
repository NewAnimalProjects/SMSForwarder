package dev.zaplatin.smsforwarder

/**
 * When an SMS was received. The platform holds SMS until the first unlock after a reboot, so
 * the broadcast time can be hours late; the service center timestamp is the real arrival time.
 */
internal object SmsTimestamp {
    fun receivedAt(serviceCenterMillis: Long, nowMillis: Long): Long = when {
        serviceCenterMillis <= 0 -> nowMillis
        else -> serviceCenterMillis.coerceAtMost(nowMillis)
    }
}
