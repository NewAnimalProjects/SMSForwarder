package dev.zaplatin.smsforwarder

import org.junit.Assert.assertEquals
import org.junit.Test

class BatteryAlertsTest {

    @Test
    fun aboveFirstThreshold_noAlerts() {
        assertEquals(emptyList<Int>(), BatteryAlerts.thresholdsToNotify(60, BatteryAlerts.RESET_THRESHOLD))
    }

    @Test
    fun crossingFirstThreshold_alertsOnce() {
        assertEquals(listOf(50), BatteryAlerts.thresholdsToNotify(50, BatteryAlerts.RESET_THRESHOLD))
    }

    @Test
    fun alreadyNotifiedThreshold_isNotRepeated() {
        assertEquals(emptyList<Int>(), BatteryAlerts.thresholdsToNotify(45, 50))
    }

    @Test
    fun bigDrop_alertsEveryCrossedThreshold() {
        assertEquals(listOf(20, 15, 10), BatteryAlerts.thresholdsToNotify(9, 50))
    }
}
