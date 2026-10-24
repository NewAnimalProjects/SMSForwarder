package dev.zaplatin.smsforwarder

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Telephony
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        OperationLogger.d(TAG, "SMS_RECEIVED broadcast received")

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: emptyArray()
        if (messages.isEmpty()) {
            OperationLogger.w(TAG, "Intent contained no SmsMessage payloads")
            return
        }

        val from = messages.first().originatingAddress ?: "unknown"
        val subId = intent?.extras?.getInt(
            "subscription",
            SubscriptionManager.INVALID_SUBSCRIPTION_ID
        ) ?: SubscriptionManager.INVALID_SUBSCRIPTION_ID
        val body = messages.joinToString(separator = "") { it.messageBody ?: "" }
        OperationLogger.d(TAG, "Parsed SMS from=$from length=${body.length}")
        val destinationNumber = resolveDestinationNumber(context, subId)

        // Keep the receiver alive while we save the SMS and try to forward it right away.
        // Sending from here does not depend on WorkManager, which battery saver may defer.
        val pendingResult = goAsync()
        receiverScope.launch {
            try {
                val dao = AppDatabase.getInstance(context).smsDao()
                val receivedAt = System.currentTimeMillis()
                val smsId = dao.insert(
                    SmsMessageEntity(
                        from = from,
                        body = body,
                        createdAt = receivedAt,
                        status = SmsStatus.PENDING,
                        destination = destinationNumber,
                        lastError = null
                    )
                )
                OperationLogger.d(TAG, "Inserted SMS row id=$smsId")
                forwardNow(context, dao, smsId, from, body, destinationNumber, receivedAt)
            } catch (e: Exception) {
                OperationLogger.e(TAG, "Failed to store SMS", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun forwardNow(
        context: Context,
        dao: SmsDao,
        smsId: Long,
        from: String,
        body: String,
        destinationNumber: String?,
        receivedAt: Long,
    ) {
        try {
            TelegramClient.sendSmsToTelegram(
                from = from,
                body = body,
                destinationNumber = destinationNumber,
                receivedAt = receivedAt,
                timeoutMs = DIRECT_SEND_TIMEOUT_MS,
            )
            dao.markSent(smsId)
            OperationLogger.d(TAG, "SMS id=$smsId forwarded directly")
        } catch (e: Exception) {
            OperationLogger.e(TAG, "Direct forward failed for id=$smsId, falling back to WorkManager", e)
            dao.markFailed(smsId, e.message)
            enqueueForwardWorker(context, smsId)
        }
    }

    private fun enqueueForwardWorker(context: Context, smsId: Long) {
        runCatching {
            val data = Data.Builder()
                .putLong(SmsForwardWorker.KEY_SMS_ID, smsId)
                .build()

            val work = OneTimeWorkRequestBuilder<SmsForwardWorker>()
                .setInputData(data)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()

            OperationLogger.d(TAG, "Enqueuing SmsForwardWorker for id=$smsId work=${work.id}")
            WorkManager.getInstance(context).enqueue(work)
        }.getOrElse { throwable ->
            OperationLogger.e(TAG, "Failed to enqueue worker", throwable)
        }
    }

    private fun resolveDestinationNumber(context: Context, subscriptionId: Int?): String? {
        if (subscriptionId == null || subscriptionId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            return null
        }
        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_PHONE_NUMBERS
            ) == PackageManager.PERMISSION_GRANTED

        if (!hasPermission) {
            OperationLogger.w(TAG, "Missing permission to read destination phone number")
            return null
        }

        val subscriptionManager = context.getSystemService(SubscriptionManager::class.java)
            ?: return null
        return runCatching {
            subscriptionManager.getActiveSubscriptionInfo(subscriptionId)?.number
        }.onFailure {
            OperationLogger.e(TAG, "Failed to resolve destination number", it)
        }.getOrNull()
    }

    companion object {
        // goAsync() allows ~10 s; leave headroom for the database work.
        private const val DIRECT_SEND_TIMEOUT_MS = 7_000L
        private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private const val TAG = "SmsReceiver"
    }
}
