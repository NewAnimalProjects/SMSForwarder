package dev.zaplatin.smsforwarder

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LockedBootNoticeTest {

    @Test
    fun firstLockedBoot_sendsNotice() {
        assertTrue(LockedBootNotice.shouldSend(bootCount = 7, lastNotifiedBootCount = null))
    }

    @Test
    fun sameBoot_doesNotRepeatNotice() {
        assertFalse(LockedBootNotice.shouldSend(bootCount = 7, lastNotifiedBootCount = 7))
    }

    @Test
    fun newBoot_sendsNoticeAgain() {
        assertTrue(LockedBootNotice.shouldSend(bootCount = 8, lastNotifiedBootCount = 7))
    }

    @Test
    fun unknownBootCount_sendsNotice() {
        // Without Settings.Global.BOOT_COUNT we cannot dedupe; a duplicate beats a missed notice.
        assertTrue(LockedBootNotice.shouldSend(bootCount = null, lastNotifiedBootCount = 7))
    }
}
