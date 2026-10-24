package dev.zaplatin.smsforwarder

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.TimeZone

class TelegramClientTest {

    @Test
    fun formatReceivedAt_usesGivenTimeZone() {
        // 2026-10-24T17:11:23Z == 2026-10-25 00:11:23 in Bangkok (UTC+7).
        val millis = 1_792_861_883_000L
        assertEquals(
            "25.10.2026 00:11:23",
            TelegramClient.formatReceivedAt(millis, TimeZone.getTimeZone("Asia/Bangkok")),
        )
    }
}
