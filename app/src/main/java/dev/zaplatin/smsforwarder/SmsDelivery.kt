package dev.zaplatin.smsforwarder

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull

/** Persistence used by [SmsDelivery]; Android implementation: [RoomSmsDeliveryStore]. */
interface SmsDeliveryStore {
    suspend fun get(id: Long): SmsMessageEntity?
    suspend fun markChatDelivered(id: Long, chatId: String)
    suspend fun markSent(id: Long)
    suspend fun markFailed(id: Long, error: String?)
}

/**
 * Forwards a stored SMS to every chat, at most once per chat as far as we can tell.
 *
 * Telegram's sendMessage is not idempotent, so a retry after a lost response duplicates the
 * message. To keep that to the one chat that timed out, each successful chat is persisted
 * right away and retries skip it. Deliveries run one at a time, so the receiver's direct send
 * and a worker never forward the same SMS concurrently.
 */
class SmsDelivery(
    private val chatIds: List<String>,
    private val store: SmsDeliveryStore,
    private val send: suspend (chatId: String, text: String, timeoutMs: Long) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val format: (SmsMessageEntity) -> String = TelegramClient::smsText,
) {
    private val mutex = Mutex()

    class TimeoutException(message: String) : Exception(message)

    /**
     * [timeoutMs] covers waiting for another delivery plus all sends. Throws on failure; the
     * SMS is then FAILED (or left untouched if the budget ran out before it was looked at).
     */
    suspend fun deliver(id: Long, timeoutMs: Long) {
        val deadline = clock() + timeoutMs
        val owner = Any()
        // The timeout can fire right after lock() succeeds, so check ownership, not the result.
        withTimeoutOrNull(timeoutMs) { mutex.lock(owner) }
        if (!mutex.holdsLock(owner)) {
            throw TimeoutException("Another delivery is still running (sms=$id)")
        }
        try {
            deliverLocked(id, deadline)
        } finally {
            mutex.unlock(owner)
        }
    }

    private suspend fun deliverLocked(id: Long, deadline: Long) {
        val sms = store.get(id) ?: return
        if (sms.status == SmsStatus.SENT) return
        try {
            val delivered = sms.deliveredChatIds()
            val text = format(sms)
            for (chatId in chatIds) {
                if (chatId in delivered) continue
                val remainingMs = deadline - clock()
                if (remainingMs <= 0) throw TimeoutException("Budget spent before chat=$chatId (sms=$id)")
                send(chatId, text, remainingMs)
                store.markChatDelivered(id, chatId)
            }
            store.markSent(id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            store.markFailed(id, e.message)
            throw e
        }
    }
}

class RoomSmsDeliveryStore(private val dao: SmsDao) : SmsDeliveryStore {
    override suspend fun get(id: Long): SmsMessageEntity? = dao.getById(id)

    /** Read-modify-write is safe: [SmsDelivery] runs deliveries one at a time. */
    override suspend fun markChatDelivered(id: Long, chatId: String) {
        val row = dao.getById(id) ?: return
        dao.setDeliveredChats(id, (row.deliveredChatIds() + chatId).joinToString(","))
    }

    override suspend fun markSent(id: Long) = dao.markSent(id)

    override suspend fun markFailed(id: Long, error: String?) = dao.markFailed(id, error)
}

/** Process-wide [SmsDelivery]: one lock shared by [SmsReceiver] and [SmsForwardWorker]. */
object SmsForwarding {
    @Volatile
    private var instance: SmsDelivery? = null

    fun delivery(context: android.content.Context): SmsDelivery = instance ?: synchronized(this) {
        instance ?: SmsDelivery(
            chatIds = TelegramClient.chatIds,
            store = RoomSmsDeliveryStore(AppDatabase.getInstance(context).smsDao()),
            send = { chatId, text, timeoutMs -> TelegramClient.sendTextToChat(chatId, text, timeoutMs) },
        ).also { instance = it }
    }
}
