package dev.zaplatin.smsforwarder

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Long-polls Telegram for bot commands and answers fresh ones from authorized chats.
 * Runs until the calling coroutine is cancelled; errors only pause it.
 */
class BotUpdatePoller(
    private val api: TelegramBotApi,
    private val handleCommand: suspend (String) -> String,
    private val commands: List<Pair<String, String>>,
    private val offsetStore: OffsetStore,
    private val authorizedChatIds: Set<String>,
    private val clock: () -> Long,
    private val networkAvailable: Flow<Unit>,
) {

    suspend fun run() {
        OperationLogger.d(TAG, "Bot polling started")
        try {
            registerCommands()
            var backoffMs = INITIAL_BACKOFF_MS
            while (true) {
                val messages = try {
                    api.getUpdates(offsetStore.offset, POLL_TIMEOUT_SEC)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val waitMs = waitAfter(e, backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
                    pause(waitMs)
                    continue
                }
                backoffMs = INITIAL_BACKOFF_MS
                for (message in messages) process(message)
            }
        } finally {
            OperationLogger.d(TAG, "Bot polling stopped")
        }
    }

    private suspend fun registerCommands() {
        try {
            api.setMyCommands(commands)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            OperationLogger.e(TAG, "setMyCommands failed: ${e.message}")
        }
    }

    private suspend fun process(message: BotMessage) {
        // Saved before handling: a command is processed at most once, even if we crash mid-way.
        offsetStore.offset = message.updateId + 1
        val text = message.text ?: return
        if (message.chatId !in authorizedChatIds) {
            OperationLogger.w(TAG, "Ignored message from unauthorized chat=${message.chatId}")
            return
        }
        if (clock() - message.dateSec * 1000 > FRESHNESS_MS) {
            OperationLogger.d(TAG, "Skipped stale message update=${message.updateId}")
            return
        }
        if (!text.startsWith("/")) return

        val command = text.trim().substringBefore(' ').substringBefore('\n').substringBefore('@')
        OperationLogger.d(TAG, "Handling $command from chat=${message.chatId}")
        val reply = try {
            handleCommand(text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            OperationLogger.e(TAG, "Command $command failed", e)
            "Ошибка: " + TelegramClient.escapeMarkdown(e.message ?: e.javaClass.simpleName)
        }
        try {
            api.sendMessage(message.chatId, reply)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            OperationLogger.e(TAG, "Failed to reply to $command in chat=${message.chatId}: ${e.message}")
        }
    }

    /** Logs the failure and returns how long to wait before the next getUpdates. */
    private fun waitAfter(error: Exception, backoffMs: Long): Long {
        val apiError = error as? TelegramApiException
        val waitMs = when (apiError?.code) {
            401 -> UNAUTHORIZED_WAIT_MS
            409 -> CONFLICT_WAIT_MS
            429 -> apiError.retryAfterSec?.let { it * 1000L } ?: backoffMs
            else -> backoffMs
        }
        val reason = when (apiError?.code) {
            401 -> "invalid bot token"
            409 -> "another getUpdates consumer or webhook is active"
            null -> "network error: ${error.message}"
            else -> "HTTP ${apiError.code}: ${apiError.description}"
        }
        OperationLogger.e(TAG, "getUpdates failed ($reason), retrying in ${waitMs / 1000} s")
        return waitMs
    }

    /** Waits [ms], or less if the network comes back. */
    private suspend fun pause(ms: Long) {
        withTimeoutOrNull(ms) { networkAvailable.first() }
    }

    companion object {
        internal const val POLL_TIMEOUT_SEC = 50
        internal const val INITIAL_BACKOFF_MS = 5_000L
        internal const val MAX_BACKOFF_MS = 300_000L
        internal const val UNAUTHORIZED_WAIT_MS = 1_800_000L
        internal const val CONFLICT_WAIT_MS = 60_000L
        internal const val FRESHNESS_MS = 300_000L
        private const val TAG = "BotUpdatePoller"
    }
}
