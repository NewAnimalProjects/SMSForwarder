package dev.zaplatin.smsforwarder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TelegramUpdatesParserTest {

    @Test
    fun parseUpdates_readsTextAndNonTextUpdates() {
        val json = """
            {"ok":true,"result":[
              {"update_id":100,"message":{"message_id":1,"date":1792861883,
                "chat":{"id":111111111,"type":"private"},"text":"/status"}},
              {"update_id":101,"edited_message":{"message_id":1,"date":1792861890,
                "chat":{"id":111111111},"text":"edited"}},
              {"update_id":102,"message":{"message_id":2,"date":1792861900,
                "chat":{"id":111111111},"sticker":{}}}
            ]}
        """.trimIndent()

        assertEquals(
            listOf(
                BotMessage(updateId = 100, chatId = "111111111", dateSec = 1792861883, text = "/status"),
                BotMessage(updateId = 101, chatId = "", dateSec = 0, text = null),
                BotMessage(updateId = 102, chatId = "111111111", dateSec = 1792861900, text = null),
            ),
            parseUpdates(json),
        )
    }

    @Test
    fun parseUpdates_emptyResult() {
        assertEquals(emptyList<BotMessage>(), parseUpdates("""{"ok":true,"result":[]}"""))
    }

    @Test
    fun apiErrorFrom_readsRetryAfter() {
        val error = apiErrorFrom(
            429,
            """{"ok":false,"error_code":429,"description":"Too Many Requests: retry after 7","parameters":{"retry_after":7}}""",
        )
        assertEquals(429, error.code)
        assertEquals(7, error.retryAfterSec)
        assertEquals("Too Many Requests: retry after 7", error.description)
    }

    @Test
    fun apiErrorFrom_toleratesNonJsonBody() {
        val error = apiErrorFrom(502, "<html>Bad Gateway</html>")
        assertEquals(502, error.code)
        assertNull(error.retryAfterSec)
        assertNull(error.description)
    }
}
