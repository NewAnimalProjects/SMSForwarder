package dev.zaplatin.smsforwarder

import org.junit.Assert.assertEquals
import org.junit.Test

class SmsTimestampTest {

    @Test
    fun usesServiceCenterTime_forSmsDeliveredLate() {
        // Held by the platform overnight until the first unlock.
        val sentAt = NOW - 6 * 60 * 60_000L
        assertEquals(sentAt, SmsTimestamp.receivedAt(serviceCenterMillis = sentAt, nowMillis = NOW))
    }

    @Test
    fun missingServiceCenterTime_fallsBackToNow() {
        assertEquals(NOW, SmsTimestamp.receivedAt(serviceCenterMillis = 0, nowMillis = NOW))
        assertEquals(NOW, SmsTimestamp.receivedAt(serviceCenterMillis = -1, nowMillis = NOW))
    }

    @Test
    fun futureServiceCenterTime_isClampedToNow() {
        // Carrier clock ahead of the phone: an SMS cannot arrive in the future.
        assertEquals(NOW, SmsTimestamp.receivedAt(serviceCenterMillis = NOW + 90_000, nowMillis = NOW))
    }

    private companion object {
        const val NOW = 1_800_000_000_000L
    }
}
