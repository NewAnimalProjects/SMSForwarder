package dev.zaplatin.smsforwarder

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SmsDeliveryTest {

    private val store = FakeStore()
    private val sent = mutableListOf<String>()
    private var failingChats = mutableSetOf<String>()
    private var sendDelayMs = 0L

    private fun TestScope.delivery() = SmsDelivery(
        chatIds = listOf(CHAT_A, CHAT_B),
        store = store,
        send = { chatId, _, timeoutMs ->
            // Like OkHttp's call timeout: a send slower than the budget fails.
            delay(minOf(sendDelayMs, timeoutMs))
            if (sendDelayMs > timeoutMs) throw java.net.SocketTimeoutException("call timeout")
            if (chatId in failingChats) throw java.net.SocketTimeoutException("timeout")
            sent += chatId
        },
        clock = { testScheduler.currentTime },
    )

    @Test
    fun deliversToEveryChatAndMarksSent() = runTest {
        store.put(sms(id = 1))

        delivery().deliver(1, timeoutMs = 10_000)

        assertEquals(listOf(CHAT_A, CHAT_B), sent)
        assertEquals(SmsStatus.SENT, store.get(1)!!.status)
    }

    @Test
    fun retryAfterPartialFailure_sendsOnlyToRemainingChats() = runTest {
        store.put(sms(id = 1))
        failingChats += CHAT_B
        val delivery = delivery()

        try {
            delivery.deliver(1, timeoutMs = 10_000)
            fail("Expected failure for chat B")
        } catch (expected: java.net.SocketTimeoutException) {
        }
        assertEquals(SmsStatus.FAILED, store.get(1)!!.status)
        assertEquals(setOf(CHAT_A), store.get(1)!!.deliveredChatIds())

        failingChats.clear()
        delivery.deliver(1, timeoutMs = 10_000)

        assertEquals(listOf(CHAT_A, CHAT_B), sent)
        assertEquals(SmsStatus.SENT, store.get(1)!!.status)
    }

    @Test
    fun alreadySent_isNotSentAgain() = runTest {
        store.put(sms(id = 1, status = SmsStatus.SENT))

        delivery().deliver(1, timeoutMs = 10_000)

        assertTrue(sent.isEmpty())
    }

    @Test
    fun concurrentDeliveries_sendEachChatOnce() = runTest {
        // The receiver's direct send and a worker can pick the same SMS at the same time.
        store.put(sms(id = 1))
        sendDelayMs = 100
        val delivery = delivery()

        val first = launch { delivery.deliver(1, timeoutMs = 10_000) }
        val second = launch { delivery.deliver(1, timeoutMs = 10_000) }
        first.join()
        second.join()

        assertEquals(listOf(CHAT_A, CHAT_B), sent)
    }

    @Test
    fun budgetSpentWaitingForLock_failsWithoutSending() = runTest {
        store.put(sms(id = 1))
        store.put(sms(id = 2))
        sendDelayMs = 5_000
        val delivery = delivery()

        val slow = launch { delivery.deliver(1, timeoutMs = 60_000) }
        delay(1)
        try {
            delivery.deliver(2, timeoutMs = 1_000)
            fail("Expected timeout while waiting for the other delivery")
        } catch (expected: SmsDelivery.TimeoutException) {
        }
        slow.join()

        assertTrue(store.get(2)!!.deliveredChatIds().isEmpty())
        assertEquals(SmsStatus.PENDING, store.get(2)!!.status)
    }

    @Test
    fun budgetRunsOutBetweenChats_keepsDeliveredChat() = runTest {
        store.put(sms(id = 1))
        sendDelayMs = 1_000

        try {
            delivery().deliver(1, timeoutMs = 1_000)
            fail("Expected timeout before chat B")
        } catch (expected: SmsDelivery.TimeoutException) {
        }

        assertEquals(setOf(CHAT_A), store.get(1)!!.deliveredChatIds())
        assertEquals(SmsStatus.FAILED, store.get(1)!!.status)
    }

    private fun sms(id: Long, status: String = SmsStatus.PENDING) = SmsMessageEntity(
        id = id,
        from = "+66000000000",
        body = "code 1234",
        createdAt = 1_800_000_000_000L,
        status = status,
        destination = null,
        lastError = null,
    )

    private class FakeStore : SmsDeliveryStore {
        private val rows = mutableMapOf<Long, SmsMessageEntity>()

        fun put(sms: SmsMessageEntity) {
            rows[sms.id] = sms
        }

        override suspend fun get(id: Long) = rows[id]

        override suspend fun markChatDelivered(id: Long, chatId: String) {
            val row = rows.getValue(id)
            rows[id] = row.copy(deliveredChats = (row.deliveredChatIds() + chatId).joinToString(","))
        }

        override suspend fun markSent(id: Long) {
            rows[id] = rows.getValue(id).copy(status = SmsStatus.SENT, lastError = null)
        }

        override suspend fun markFailed(id: Long, error: String?) {
            rows[id] = rows.getValue(id).copy(status = SmsStatus.FAILED, lastError = error)
        }
    }

    private companion object {
        const val CHAT_A = "111"
        const val CHAT_B = "222"
    }
}
