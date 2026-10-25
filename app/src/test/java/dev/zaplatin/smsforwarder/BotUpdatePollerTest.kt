package dev.zaplatin.smsforwarder

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BotUpdatePollerTest {

    private val api = FakeBotApi()
    private val store = InMemoryOffsetStore()
    private val network = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val handled = mutableListOf<String>()
    private var handler: suspend (String) -> String = { text ->
        handled += text
        "reply:$text"
    }

    @Test
    fun registersCommandsAndAnswersFreshCommand() = runTest {
        api.responses += { listOf(message(1, "/status")) }

        startPoller()
        runCurrent()

        assertEquals(COMMANDS, api.registeredCommands)
        assertEquals(listOf(AUTHORIZED to "reply:/status"), api.sent)
        assertEquals(2L, store.offset)
    }

    @Test
    fun setMyCommandsFailureDoesNotStopPolling() = runTest {
        api.setCommandsFailure = TelegramApiException(500, null, "oops")
        api.responses += { listOf(message(1, "/ping")) }

        startPoller()
        runCurrent()

        assertEquals(listOf(AUTHORIZED to "reply:/ping"), api.sent)
    }

    @Test
    fun answersSeveralMessagesInOrder() = runTest {
        api.responses += { listOf(message(1, "/status"), message(2, "/ping")) }

        startPoller()
        runCurrent()

        assertEquals(listOf("/status", "/ping"), handled)
        assertEquals(3L, store.offset)
    }

    @Test
    fun ignoresUnauthorizedChat() = runTest {
        api.responses += { listOf(message(1, "/status", chatId = "999")) }

        startPoller()
        runCurrent()

        assertTrue(api.sent.isEmpty())
        assertEquals(2L, store.offset)
    }

    @Test
    fun freshnessWindowIsFiveMinutesInclusive() = runTest {
        api.responses += {
            listOf(
                message(1, "/old", ageMs = BotUpdatePoller.FRESHNESS_MS + 1_000),
                message(2, "/edge", ageMs = BotUpdatePoller.FRESHNESS_MS),
            )
        }

        startPoller()
        runCurrent()

        assertEquals(listOf("/edge"), handled)
    }

    @Test
    fun answersCommandDatedSlightlyInFuture() = runTest {
        api.responses += { listOf(message(1, "/status", ageMs = -30_000)) }

        startPoller()
        runCurrent()

        assertEquals(listOf("/status"), handled)
    }

    @Test
    fun ignoresNonCommandsButAdvancesOffset() = runTest {
        api.responses += { listOf(message(1, "hello"), message(2, null)) }

        startPoller()
        runCurrent()

        assertTrue(handled.isEmpty())
        assertTrue(api.sent.isEmpty())
        assertEquals(3L, store.offset)
    }

    @Test
    fun savesOffsetBeforeHandling() = runTest {
        var offsetSeenByHandler: Long? = null
        handler = {
            offsetSeenByHandler = store.offset
            "ok"
        }
        api.responses += { listOf(message(7, "/status")) }

        startPoller()
        runCurrent()

        assertEquals(8L, offsetSeenByHandler)
    }

    @Test
    fun resumesFromStoredOffset() = runTest {
        store.offset = 42

        startPoller()
        runCurrent()

        assertEquals(42L, api.getUpdatesCalls.single().first)
    }

    @Test
    fun handlerFailureRepliesWithErrorAndContinues() = runTest {
        handler = { text -> if (text == "/status") error("boom") else "ok" }
        api.responses += { listOf(message(1, "/status"), message(2, "/ping")) }

        startPoller()
        runCurrent()

        assertEquals(listOf(AUTHORIZED to "Ошибка: boom", AUTHORIZED to "ok"), api.sent)
    }

    @Test
    fun sendFailureDoesNotStopLoop() = runTest {
        api.sendFailure = TelegramApiException(400, null, "Bad Request")
        api.responses += { listOf(message(1, "/status")) }

        startPoller()
        runCurrent()

        assertEquals(2, api.getUpdatesCalls.size)
    }

    @Test
    fun backoffDoublesAndResetsAfterSuccess() = runTest {
        repeat(3) { api.responses += { throw TelegramApiException(null, null, "offline") } }
        api.responses += { emptyList() }
        api.responses += { throw TelegramApiException(null, null, "offline") }

        startPoller()
        advanceTimeBy(60_000)
        runCurrent()

        assertEquals(
            listOf(0L, 5_000L, 15_000L, 35_000L, 35_000L, 40_000L),
            api.getUpdatesCalls.map { it.second },
        )
    }

    @Test
    fun backoffIsCappedAtFiveMinutes() = runTest {
        repeat(10) { api.responses += { throw TelegramApiException(null, null, "offline") } }

        startPoller()
        advanceTimeBy(3_000_000)
        runCurrent()

        val waits = api.getUpdatesCalls.map { it.second }.zipWithNext { a, b -> b - a }
        assertEquals(
            listOf(5_000L, 10_000L, 20_000L, 40_000L, 80_000L, 160_000L, 300_000L, 300_000L, 300_000L, 300_000L),
            waits,
        )
    }

    @Test
    fun tooManyRequestsHonorsRetryAfter() = runTest {
        api.responses += { throw TelegramApiException(429, 7, "Too Many Requests") }

        startPoller()
        advanceTimeBy(60_000)
        runCurrent()

        assertEquals(7_000L, api.getUpdatesCalls[1].second)
    }

    @Test
    fun unauthorizedWaitsThirtyMinutes() = runTest {
        api.responses += { throw TelegramApiException(401, null, "Unauthorized") }

        startPoller()
        advanceTimeBy(BotUpdatePoller.UNAUTHORIZED_WAIT_MS - 1)
        runCurrent()
        assertEquals(1, api.getUpdatesCalls.size)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, api.getUpdatesCalls.size)
    }

    @Test
    fun conflictWaitsOneMinute() = runTest {
        api.responses += { throw TelegramApiException(409, null, "Conflict") }

        startPoller()
        advanceTimeBy(BotUpdatePoller.CONFLICT_WAIT_MS)
        runCurrent()

        assertEquals(BotUpdatePoller.CONFLICT_WAIT_MS, api.getUpdatesCalls[1].second)
    }

    @Test
    fun networkAvailableEndsWaitEarly() = runTest {
        api.responses += { throw TelegramApiException(null, null, "offline") }

        startPoller()
        runCurrent()
        advanceTimeBy(1_000)
        network.tryEmit(Unit)
        runCurrent()

        assertEquals(listOf(0L, 1_000L), api.getUpdatesCalls.map { it.second })
    }

    @Test
    fun cancellationStopsLoop() = runTest {
        val job = startPoller()
        runCurrent()

        job.cancelAndJoin()

        assertTrue(job.isCancelled)
    }

    private fun TestScope.startPoller(): Job {
        api.timeSource = { testScheduler.currentTime }
        val poller = BotUpdatePoller(
            api = api,
            handleCommand = { handler(it) },
            commands = COMMANDS,
            offsetStore = store,
            authorizedChatIds = setOf(AUTHORIZED),
            clock = { NOW_MS + testScheduler.currentTime },
            networkAvailable = network,
        )
        return backgroundScope.launch { poller.run() }
    }

    private fun message(id: Long, text: String?, chatId: String = AUTHORIZED, ageMs: Long = 0) =
        BotMessage(updateId = id, chatId = chatId, dateSec = (NOW_MS - ageMs) / 1000, text = text)

    private class FakeBotApi : TelegramBotApi {
        val responses = ArrayDeque<() -> List<BotMessage>>()
        val getUpdatesCalls = mutableListOf<Pair<Long?, Long>>()
        val sent = mutableListOf<Pair<String, String>>()
        var registeredCommands: List<Pair<String, String>>? = null
        var setCommandsFailure: Exception? = null
        var sendFailure: Exception? = null
        var timeSource: () -> Long = { 0L }

        override suspend fun getUpdates(offset: Long?, timeoutSec: Int): List<BotMessage> {
            getUpdatesCalls += offset to timeSource()
            val next = responses.removeFirstOrNull() ?: awaitCancellation()
            return next()
        }

        override suspend fun sendMessage(chatId: String, text: String) {
            sendFailure?.let { throw it }
            sent += chatId to text
        }

        override suspend fun setMyCommands(commands: List<Pair<String, String>>) {
            setCommandsFailure?.let { throw it }
            registeredCommands = commands
        }
    }

    private class InMemoryOffsetStore : OffsetStore {
        override var offset: Long? = null
    }

    private companion object {
        const val AUTHORIZED = "111111111"
        const val NOW_MS = 1_800_000_000_000L
        val COMMANDS = listOf("status" to "состояние телефона")
    }
}
