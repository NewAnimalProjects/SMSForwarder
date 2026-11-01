package dev.zaplatin.smsforwarder

import android.content.Intent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BootNoticeTest {

    @Test
    fun firstNoticeForThisBoot_isSent() {
        assertTrue(BootNotice.shouldSend(bootCount = 7, lastNotifiedBootCount = null, sentInProcess = false))
        assertTrue(BootNotice.shouldSend(bootCount = 8, lastNotifiedBootCount = 7, sentInProcess = false))
    }

    @Test
    fun sameBoot_doesNotRepeatNotice() {
        // LOCKED_BOOT_COMPLETED and BOOT_COMPLETED both arrive in one boot.
        assertFalse(BootNotice.shouldSend(bootCount = 7, lastNotifiedBootCount = 7, sentInProcess = false))
    }

    @Test
    fun unknownBootCount_dedupesWithinProcess() {
        assertTrue(BootNotice.shouldSend(bootCount = null, lastNotifiedBootCount = 7, sentInProcess = false))
        assertFalse(BootNotice.shouldSend(bootCount = null, lastNotifiedBootCount = 7, sentInProcess = true))
    }

    @Test
    fun noticeText_dependsOnLockState() {
        val locked = TelegramClient.bootNoticeText(userUnlocked = false)
        val unlocked = TelegramClient.bootNoticeText(userUnlocked = true)

        assertTrue(locked, locked.startsWith("🔄 *Телефон перезагрузился*"))
        assertTrue(unlocked, unlocked.startsWith("🔄 *Телефон перезагрузился*"))
        assertTrue(locked, locked.contains("после первой разблокировки"))
        assertFalse(unlocked, unlocked.contains("разблокир"))
        assertNotEquals(locked, unlocked)
    }

    @Test
    fun onlyBootBroadcastsTriggerNotice() {
        assertTrue(BootReceiver.isBootAction(Intent.ACTION_LOCKED_BOOT_COMPLETED))
        assertTrue(BootReceiver.isBootAction(Intent.ACTION_BOOT_COMPLETED))
        // An app update restarts the service without a reboot.
        assertFalse(BootReceiver.isBootAction(Intent.ACTION_MY_PACKAGE_REPLACED))
    }
}
