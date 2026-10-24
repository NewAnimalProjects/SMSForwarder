package dev.zaplatin.smsforwarder

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BotCommandHandlerTest {

    private val status = FakeStatus()
    private val handler = BotCommandHandler(status)

    @Test
    fun status_showsAllFields() = runTest {
        status.messages = listOf(sms(id = 1, createdAt = NOW - 60_000))

        val reply = handler.handle("/status")

        assertTrue(reply, reply.startsWith("📊 *Статус*"))
        assertTrue(reply, reply.contains("*Батарея:* 76%, заряжается (USB)"))
        assertTrue(reply, reply.contains("*Сервис работает:* 2 ч 15 мин"))
        assertTrue(reply, reply.contains("*SMS всего:* 12, не отправлено: 1"))
        assertTrue(reply, reply.contains("*Последняя SMS:* " + TelegramClient.formatReceivedAt(NOW - 60_000)))
    }

    @Test
    fun status_serviceNotStartedAndNoSms() = runTest {
        status.startedAt = null

        val reply = handler.handle("/status")

        assertTrue(reply, reply.contains("*Сервис работает:* не запущен"))
        assertTrue(reply, reply.contains("*Последняя SMS:* нет"))
    }

    @Test
    fun battery_reportsStateOrMissingData() = runTest {
        status.batteryStatus = BatteryStatus(percent = 54, isCharging = false, plugType = null)
        assertEquals("🔋 *Батарея:* 54%, не заряжается", handler.handle("/battery"))

        status.batteryStatus = null
        assertEquals("🔋 *Батарея:* нет данных", handler.handle("/battery"))
    }

    @Test
    fun last_defaultsToFive() = runTest {
        handler.handle("/last")
        assertEquals(5, status.requestedLimit)
    }

    @Test
    fun last_clampsCount() = runTest {
        handler.handle("/last 50")
        assertEquals(20, status.requestedLimit)

        handler.handle("/last 0")
        assertEquals(1, status.requestedLimit)
    }

    @Test
    fun last_nonNumericCountShowsUsage() = runTest {
        assertEquals(BotCommandHandler.LAST_USAGE_TEXT, handler.handle("/last many"))
    }

    @Test
    fun last_noMessages() = runTest {
        assertEquals("SMS пока нет.", handler.handle("/last"))
    }

    @Test
    fun last_listsMessagesNewestFirst() = runTest {
        status.messages = listOf(
            sms(id = 2, body = "second", status = SmsStatus.PENDING),
            sms(id = 1, body = "first", status = SmsStatus.SENT),
        )

        val reply = handler.handle("/last 2")

        assertTrue(reply, reply.startsWith("📨 *Последние SMS (2):*"))
        assertTrue(reply, reply.indexOf("second") < reply.indexOf("first"))
        assertTrue(reply, reply.contains("*Статус:* ожидает отправки"))
        assertTrue(reply, reply.contains("*Статус:* отправлено"))
    }

    @Test
    fun last_truncatesLongBody() = runTest {
        status.messages = listOf(sms(id = 1, body = "a".repeat(600)))

        val reply = handler.handle("/last 1")

        assertTrue(reply.contains("a".repeat(500) + "…"))
        assertFalse(reply.contains("a".repeat(501)))
    }

    @Test
    fun last_capsReplyLength() = runTest {
        status.messages = (20 downTo 1).map { sms(id = it.toLong(), body = "x".repeat(500)) }

        val reply = handler.handle("/last 20")

        assertTrue("length ${reply.length}", reply.length <= 4096)
        assertTrue(reply, Regex("…и ещё \\d+$").containsMatchIn(reply))
    }

    @Test
    fun last_escapesMarkdownInSenderAndBody() = runTest {
        status.messages = listOf(sms(id = 1, from = "+66_1", body = "a*b_c"))

        val reply = handler.handle("/last 1")

        assertTrue(reply, reply.contains("+66\\_1"))
        assertTrue(reply, reply.contains("a\\*b\\_c"))
    }

    @Test
    fun acceptsBotNameSuffixCaseAndExtraWhitespace() = runTest {
        assertTrue(handler.handle("/STATUS@SmsForwarderBot").startsWith("📊 *Статус*"))

        handler.handle("  /last   3  ")
        assertEquals(3, status.requestedLimit)

        handler.handle("/last\n4")
        assertEquals(4, status.requestedLimit)
    }

    @Test
    fun sims_listsSimsWithUnknownNumbers() = runTest {
        assertEquals(
            "📶 *SIM-карты:*\nSIM 1: +66000000001\ndtac: номер недоступен",
            handler.handle("/sims"),
        )
    }

    @Test
    fun sims_permissionMissingOrEmpty() = runTest {
        status.simsResult = SimsResult.PermissionMissing
        assertEquals("Нет доступа к данным SIM (нужно разрешение телефона).", handler.handle("/sims"))

        status.simsResult = SimsResult.Available(emptyList())
        assertEquals("SIM-карты не найдены.", handler.handle("/sims"))
    }

    @Test
    fun ping_helpStartAndUnknown() = runTest {
        assertEquals("pong · " + TelegramClient.formatReceivedAt(NOW), handler.handle("/ping"))
        assertEquals(BotCommandHandler.HELP_TEXT, handler.handle("/help"))
        assertEquals(BotCommandHandler.HELP_TEXT, handler.handle("/start"))
        assertEquals(BotCommandHandler.UNKNOWN_COMMAND_TEXT, handler.handle("/foo"))
        assertTrue(BotCommandHandler.HELP_TEXT.contains("/status"))
    }

    @Test
    fun formatDuration() {
        assertEquals("меньше минуты", BotCommandHandler.formatDuration(30_000))
        assertEquals("1 ч 1 мин", BotCommandHandler.formatDuration(61 * 60_000L))
        assertEquals("1 д 5 мин", BotCommandHandler.formatDuration((24 * 60 + 5) * 60_000L))
    }

    private fun sms(
        id: Long,
        from: String = "+66000000000",
        body: String = "hello",
        createdAt: Long = NOW - id * 60_000,
        status: String = SmsStatus.SENT,
    ) = SmsMessageEntity(
        id = id,
        from = from,
        body = body,
        createdAt = createdAt,
        status = status,
        destination = null,
        lastError = null,
    )

    private class FakeStatus : DeviceStatusProvider {
        var batteryStatus: BatteryStatus? = BatteryStatus(percent = 76, isCharging = true, plugType = PlugType.USB)
        var simsResult: SimsResult = SimsResult.Available(
            listOf(SimInfo("SIM 1", "+66000000001"), SimInfo("dtac", null)),
        )
        var messages: List<SmsMessageEntity> = emptyList()
        var startedAt: Long? = NOW - (2 * 60 + 15) * 60_000L
        var requestedLimit: Int? = null

        override fun battery() = batteryStatus
        override fun sims() = simsResult
        override suspend fun smsCounts() = 12 to 1
        override suspend fun lastSms(limit: Int): List<SmsMessageEntity> {
            requestedLimit = limit
            return messages.take(limit)
        }
        override fun serviceStartedAt() = startedAt
        override fun now() = NOW
    }

    private companion object {
        const val NOW = 1_800_000_000_000L
    }
}
