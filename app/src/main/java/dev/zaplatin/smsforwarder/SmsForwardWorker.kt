package dev.zaplatin.smsforwarder

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class SmsForwardWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        OperationLogger.d(TAG, "Worker ${id} starting")
        val dao = AppDatabase.getInstance(applicationContext).smsDao()
        val smsId = inputData.getLong(KEY_SMS_ID, NO_ID)
        val minCreatedAt = System.currentTimeMillis() - MAX_MESSAGE_AGE_MS
        val messages = if (smsId != NO_ID) {
            dao.getById(smsId)?.let { listOf(it) } ?: emptyList()
        } else {
            dao.getAllNotSent(
                minCreatedAt = minCreatedAt,
                limit = MAX_MESSAGES_PER_RUN
            )
        }

        if (messages.isEmpty()) {
            OperationLogger.d(TAG, "No messages to process (smsId=$smsId)")
            return Result.success()
        }
        OperationLogger.d(TAG, "Processing ${messages.size} queued messages (smsId=$smsId)")

        var hadError = false
        for (message in messages) {
            try {
                if (message.createdAt < minCreatedAt) {
                    OperationLogger.w(TAG, "Message ${message.id} older than retention window, marking failed")
                    dao.markFailed(message.id, "Message older than 7 days")
                    continue
                }
                OperationLogger.d(TAG, "Forwarding message ${message.id} from=${message.from}")
                TelegramClient.sendSmsToTelegram(
                    from = message.from,
                    body = message.body,
                    destinationNumber = message.destination,
                    receivedAt = message.createdAt,
                )
                dao.markSent(message.id)
                OperationLogger.d(TAG, "Message ${message.id} marked as sent")
            } catch (e: Exception) {
                hadError = true
                OperationLogger.e(TAG, "Failed to forward message ${message.id}", e)
                dao.markFailed(message.id, e.message)
            }
        }

        val result = if (hadError) Result.retry() else Result.success()
        OperationLogger.d(TAG, "Worker ${id} finished with result=$result")
        return result
    }

    companion object {
        const val KEY_SMS_ID = "key_sms_id"
        private const val NO_ID = -1L
        private const val DAYS = 7
        private const val MILLIS_IN_DAY = 24 * 60 * 60 * 1000L
        private const val MAX_MESSAGE_AGE_MS = DAYS * MILLIS_IN_DAY
        private const val MAX_MESSAGES_PER_RUN = 20
        private const val TAG = "SmsForwardWorker"
    }
}
