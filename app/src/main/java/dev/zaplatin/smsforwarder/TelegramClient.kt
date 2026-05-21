package dev.zaplatin.smsforwarder

import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

object TelegramClient {
    private const val BOT_TOKEN = "PUT_YOUR"
    private val CHAT_IDS = listOf(
        "PUT_YOUR",
    )
    private val client = OkHttpClient()

    fun sendSmsToTelegram(from: String, body: String, destinationNumber: String?) {
        require(!BOT_TOKEN.contains("PUT_YOUR")) { "Telegram bot token must be configured." }
        require(CHAT_IDS.isNotEmpty()) { "At least one Telegram chat id must be configured." }
        require(CHAT_IDS.none { it.contains("PUT_YOUR") }) { "Telegram chat ids must be configured." }
        OperationLogger.d(TAG, "Sending SMS to Telegram from=$from bodyLength=${body.length} chats=${CHAT_IDS.size}")

        val text = buildString {
            append("\uD83D\uDCE9 *Новая SMS*\n")
            append("*От:* ").append(escapeMarkdown(from)).append('\n')
            if (!destinationNumber.isNullOrBlank()) {
                append("*На номер:* ")
                    .append(escapeMarkdown(destinationNumber))
                    .append('\n')
            }
            append("*Текст:*\n")
            append(escapeMarkdown(body))
        }

        CHAT_IDS.forEach { chatId ->
            val bodyPayload = FormBody.Builder()
                .add("chat_id", chatId)
                .add("text", text)
                .add("parse_mode", "Markdown")
                .build()

            val request = Request.Builder()
                .url("https://api.telegram.org/bot$BOT_TOKEN/sendMessage")
                .post(bodyPayload)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    OperationLogger.e(TAG, "Telegram API error code=${response.code} chat=$chatId")
                    throw IllegalStateException("Telegram API error: ${response.code}")
                }
                OperationLogger.d(TAG, "Telegram API success code=${response.code} chat=$chatId")
            }
        }
    }

    fun sendBatteryLowToTelegram(currentPercent: Int) {
        require(!BOT_TOKEN.contains("PUT_YOUR")) { "Telegram bot token must be configured." }
        require(CHAT_IDS.isNotEmpty()) { "At least one Telegram chat id must be configured." }
        require(CHAT_IDS.none { it.contains("PUT_YOUR") }) { "Telegram chat ids must be configured." }

        val safePercent = currentPercent.coerceIn(0, 100)
        val text = buildString {
            append("\u26A0\uFE0F *Низкий заряд аккумулятора*\n")
            append("*Текущий заряд:* ").append(safePercent).append("%\n")
            append("Необходимо зарядить устройство.")
        }

        CHAT_IDS.forEach { chatId ->
            val bodyPayload = FormBody.Builder()
                .add("chat_id", chatId)
                .add("text", text)
                .add("parse_mode", "Markdown")
                .build()

            val request = Request.Builder()
                .url("https://api.telegram.org/bot$BOT_TOKEN/sendMessage")
                .post(bodyPayload)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    OperationLogger.e(TAG, "Telegram API error code=${response.code} chat=$chatId")
                    throw IllegalStateException("Telegram API error: ${response.code}")
                }
                OperationLogger.d(TAG, "Telegram API success code=${response.code} chat=$chatId")
            }
        }
    }

    private fun escapeMarkdown(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("_", "\\_")
            .replace("*", "\\*")
            .replace("[", "\\[")
            .replace("]", "\\]")
            .replace("`", "\\`")
            .replace("(", "\\(")
            .replace(")", "\\)")
    }

    private const val TAG = "TelegramClient"
}
