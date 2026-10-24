package dev.zaplatin.smsforwarder

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

object TelegramClient : TelegramBotApi {
    // Configured via telegram.botToken / telegram.chatIds in local.properties.
    private const val BOT_TOKEN = BuildConfig.TELEGRAM_BOT_TOKEN
    private val CHAT_IDS = BuildConfig.TELEGRAM_CHAT_IDS
        .split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
    private val client = OkHttpClient()

    // getUpdates holds the request open for up to 50 s, longer than OkHttp's default 10 s read timeout.
    private val pollingClient by lazy {
        client.newBuilder()
            .readTimeout(LONG_POLL_READ_TIMEOUT_SEC, TimeUnit.SECONDS)
            .build()
    }

    val authorizedChatIds: Set<String>
        get() = CHAT_IDS.toSet()

    override suspend fun getUpdates(offset: Long?, timeoutSec: Int): List<BotMessage> {
        val form = FormBody.Builder()
            .add("timeout", timeoutSec.toString())
            .add("allowed_updates", "[\"message\"]")
        if (offset != null) form.add("offset", offset.toString())
        return parseUpdates(callApi(pollingClient, "getUpdates", form.build()))
    }

    override suspend fun sendMessage(chatId: String, text: String) {
        val form = FormBody.Builder()
            .add("chat_id", chatId)
            .add("text", text)
            .add("parse_mode", "Markdown")
            .build()
        callApi(client, "sendMessage", form)
    }

    override suspend fun setMyCommands(commands: List<Pair<String, String>>) {
        val json = JSONArray()
        commands.forEach { (command, description) ->
            json.put(JSONObject().put("command", command).put("description", description))
        }
        callApi(client, "setMyCommands", FormBody.Builder().add("commands", json.toString()).build())
    }

    /** Returns the response body; throws [TelegramApiException] on HTTP or network errors. */
    private suspend fun callApi(httpClient: OkHttpClient, method: String, body: RequestBody): String {
        val request = Request.Builder()
            .url("https://api.telegram.org/bot$BOT_TOKEN/$method")
            .post(body)
            .build()
        return try {
            httpClient.newCall(request).await().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw apiErrorFrom(response.code, text)
                text
            }
        } catch (e: IOException) {
            throw TelegramApiException(code = null, retryAfterSec = null, description = e.message, cause = e)
        }
    }

    /** Suspends until the call completes; cancelling the coroutine cancels the HTTP call. */
    @OptIn(ExperimentalCoroutinesApi::class) // resume(value, onCancellation) closes a response that arrives after cancellation
    private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { _ -> response.close() }
            }
        })
    }

    fun sendSmsToTelegram(
        from: String,
        body: String,
        destinationNumber: String?,
        receivedAt: Long,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ) {
        OperationLogger.d(TAG, "Sending SMS to Telegram from=$from bodyLength=${body.length} chats=${CHAT_IDS.size}")

        val text = buildString {
            append("\uD83D\uDCE9 *Новая SMS*\n")
            append("*От:* ").append(escapeMarkdown(from)).append('\n')
            if (!destinationNumber.isNullOrBlank()) {
                append("*На номер:* ")
                    .append(escapeMarkdown(destinationNumber))
                    .append('\n')
            }
            append("*Получено:* ").append(formatReceivedAt(receivedAt)).append('\n')
            append("*Текст:*\n")
            append(escapeMarkdown(body))
        }
        sendToAllChats(text, timeoutMs)
    }

    fun sendBatteryLowToTelegram(currentPercent: Int) {
        val safePercent = currentPercent.coerceIn(0, 100)
        val text = buildString {
            append("\u26A0\uFE0F *Низкий заряд аккумулятора*\n")
            append("*Текущий заряд:* ").append(safePercent).append("%\n")
            append("Необходимо зарядить устройство.")
        }
        sendToAllChats(text, DEFAULT_TIMEOUT_MS)
    }

    /**
     * Sends [text] to every configured chat. [timeoutMs] is the budget for all chats together,
     * so callers with a hard deadline (e.g. a BroadcastReceiver) never block longer than that.
     */
    private fun sendToAllChats(text: String, timeoutMs: Long) {
        require(BOT_TOKEN.isNotBlank()) { "Set telegram.botToken in local.properties." }
        require(CHAT_IDS.isNotEmpty()) { "Set telegram.chatIds in local.properties." }

        val deadline = System.currentTimeMillis() + timeoutMs
        CHAT_IDS.forEach { chatId ->
            val remainingMs = deadline - System.currentTimeMillis()
            check(remainingMs > 0) { "Telegram send timed out before chat=$chatId" }

            val bodyPayload = FormBody.Builder()
                .add("chat_id", chatId)
                .add("text", text)
                .add("parse_mode", "Markdown")
                .build()

            val request = Request.Builder()
                .url("https://api.telegram.org/bot$BOT_TOKEN/sendMessage")
                .post(bodyPayload)
                .build()

            val call = client.newCall(request)
            call.timeout().timeout(remainingMs, TimeUnit.MILLISECONDS)
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    OperationLogger.e(TAG, "Telegram API error code=${response.code} chat=$chatId")
                    throw IllegalStateException("Telegram API error: ${response.code}")
                }
                OperationLogger.d(TAG, "Telegram API success code=${response.code} chat=$chatId")
            }
        }
    }

    /** Device-local time, e.g. 25.10.2026 00:11:23. */
    internal fun formatReceivedAt(millis: Long, timeZone: TimeZone = TimeZone.getDefault()): String {
        // SimpleDateFormat is not thread-safe, so create one per call.
        val format = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.ROOT)
        format.timeZone = timeZone
        return format.format(Date(millis))
    }

    internal fun escapeMarkdown(value: String): String {
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

    private const val DEFAULT_TIMEOUT_MS = 60_000L
    private const val LONG_POLL_READ_TIMEOUT_SEC = 60L
    private const val TAG = "TelegramClient"
}
