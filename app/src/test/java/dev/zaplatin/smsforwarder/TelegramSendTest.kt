package dev.zaplatin.smsforwarder

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/** sendMessage is not idempotent: a request Telegram may have received must never be resent. */
class TelegramSendTest {

    private val server = MockWebServer().apply { start() }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun connectionDroppedAfterRequest_isNotRetriedByOkHttp() {
        // OkHttp silently resends a POST when a reused connection fails after the request was
        // written: Telegram gets the message twice. Seen on device as "IOException: Canceled"
        // thrown from RetryAndFollowUpInterceptor.
        server.enqueue(MockResponse().setBody("""{"ok":true}"""))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        server.enqueue(MockResponse().setBody("""{"ok":true}"""))

        send()
        try {
            send()
            fail("Expected the dropped connection to fail the call")
        } catch (expected: IOException) {
        }

        assertEquals(2, server.requestCount)
    }

    @Test
    fun everySendOpensFreshConnection() {
        // A pooled connection can die silently while the phone sleeps; the next request then
        // reaches Telegram but its response is lost.
        repeat(2) { server.enqueue(MockResponse().setBody("""{"ok":true}""")) }

        send()
        send()

        server.takeRequest()
        assertEquals(0, server.takeRequest().sequenceNumber)
    }

    private fun send() {
        val request = TelegramClient.sendMessageRequest(server.url("/sendMessage"), chatId = "111", text = "hi")
        TelegramClient.sendClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
        }
    }
}
