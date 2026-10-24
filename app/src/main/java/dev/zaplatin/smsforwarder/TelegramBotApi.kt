package dev.zaplatin.smsforwarder

import org.json.JSONObject

/** The part of the Telegram Bot API used for bot commands. Implemented by [TelegramClient]. */
interface TelegramBotApi {
    suspend fun getUpdates(offset: Long?, timeoutSec: Int): List<BotMessage>
    suspend fun sendMessage(chatId: String, text: String)
    suspend fun setMyCommands(commands: List<Pair<String, String>>)
}

/** One incoming update. For updates without a message, [chatId] is empty and [text] is null. */
data class BotMessage(
    val updateId: Long,
    val chatId: String,
    val dateSec: Long,
    val text: String?,
)

/** [code] is null for network errors; [retryAfterSec] is set for HTTP 429. */
class TelegramApiException(
    val code: Int?,
    val retryAfterSec: Int?,
    val description: String?,
    cause: Throwable? = null,
) : Exception("Telegram API error ${code ?: "network"}: $description", cause)

internal fun parseUpdates(json: String): List<BotMessage> {
    val result = JSONObject(json).getJSONArray("result")
    return (0 until result.length()).map { index ->
        val update = result.getJSONObject(index)
        val message = update.optJSONObject("message")
        BotMessage(
            updateId = update.getLong("update_id"),
            chatId = message?.optJSONObject("chat")?.optLong("id")?.toString().orEmpty(),
            dateSec = message?.optLong("date") ?: 0L,
            text = if (message != null && message.has("text")) message.getString("text") else null,
        )
    }
}

internal fun apiErrorFrom(code: Int, body: String): TelegramApiException {
    val json = runCatching { JSONObject(body) }.getOrNull()
    return TelegramApiException(
        code = code,
        retryAfterSec = json?.optJSONObject("parameters")?.optInt("retry_after", 0)?.takeIf { it > 0 },
        description = json?.optString("description")?.takeIf { it.isNotEmpty() },
    )
}
