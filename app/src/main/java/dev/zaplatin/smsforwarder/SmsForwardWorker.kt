package dev.zaplatin.smsforwarder

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException

/** Forwards every SMS that is not sent yet. Runs as one serial chain, see [enqueue]. */
class SmsForwardWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        OperationLogger.d(TAG, "Worker ${id} starting")
        val dao = AppDatabase.getInstance(applicationContext).smsDao()
        val delivery = SmsForwarding.delivery(applicationContext)
        val minCreatedAt = System.currentTimeMillis() - MAX_MESSAGE_AGE_MS
        val messages = dao.getAllNotSent(minCreatedAt = minCreatedAt, limit = MAX_MESSAGES_PER_RUN)

        if (messages.isEmpty()) {
            OperationLogger.d(TAG, "No messages to process")
            return Result.success()
        }
        OperationLogger.d(TAG, "Processing ${messages.size} queued messages")

        var hadError = false
        for (message in messages) {
            try {
                OperationLogger.d(TAG, "Forwarding message ${message.id} from=${message.from}")
                delivery.deliver(message.id, SEND_TIMEOUT_MS)
                OperationLogger.d(TAG, "Message ${message.id} done")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                hadError = true
                OperationLogger.e(TAG, "Failed to forward message ${message.id}", e)
            }
        }

        val result = if (hadError) Result.retry() else Result.success()
        OperationLogger.d(TAG, "Worker ${id} finished with result=$result")
        return result
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "sms_forward"
        private const val DAYS = 7
        private const val MILLIS_IN_DAY = 24 * 60 * 60 * 1000L
        private const val MAX_MESSAGE_AGE_MS = DAYS * MILLIS_IN_DAY
        private const val MAX_MESSAGES_PER_RUN = 20
        private const val SEND_TIMEOUT_MS = 60_000L
        private const val TAG = "SmsForwardWorker"

        /**
         * Appends to a single unique chain, so two workers never process the same SMS at once.
         * Each run picks up every unsent SMS, so a queued run behind a retrying one is cheap.
         */
        fun enqueue(context: Context) {
            val work = OneTimeWorkRequestBuilder<SmsForwardWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            OperationLogger.d(TAG, "Enqueuing SmsForwardWorker work=${work.id}")
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, work)
        }
    }
}
