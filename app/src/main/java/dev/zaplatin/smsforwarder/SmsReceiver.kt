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
import kotlinx.coroutines.runBlocking

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

        val smsId = runBlocking {
            val dao = AppDatabase.getInstance(context).smsDao()
            dao.insert(
                SmsMessageEntity(
                    from = from,
                    body = body,
                    createdAt = System.currentTimeMillis(),
                    status = SmsStatus.PENDING,
                    destination = destinationNumber,
                    lastError = null
                )
            )
        }

        OperationLogger.d(TAG, "Inserted SMS row id=$smsId")
        enqueueForwardWorker(context, smsId)
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
        private const val TAG = "SmsReceiver"
    }
}
