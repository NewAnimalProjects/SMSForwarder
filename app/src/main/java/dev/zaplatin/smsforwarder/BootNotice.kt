package dev.zaplatin.smsforwarder

import android.content.Context
import android.provider.Settings
import androidx.core.content.edit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Tells Telegram that the phone rebooted, at most once per boot. Before the first unlock the
 * platform holds incoming SMS, so the text says when forwarding resumes.
 */
internal object BootNotice {
    private const val PREFS_NAME = "boot_notice_prefs"
    private const val KEY_LAST_NOTIFIED_BOOT = "last_notified_boot_count"
    private const val INITIAL_BACKOFF_MS = 10_000L
    private const val MAX_BACKOFF_MS = 300_000L
    private const val TAG = "BootNotice"

    /** Fallback dedupe when Settings.Global.BOOT_COUNT is unavailable. */
    @Volatile
    private var sentInProcess = false

    internal fun shouldSend(bootCount: Int?, lastNotifiedBootCount: Int?, sentInProcess: Boolean): Boolean =
        if (bootCount != null) bootCount != lastNotifiedBootCount else !sentInProcess

    /** Retries until sent; the lock state is re-read on every attempt to pick the text. */
    suspend fun sendIfNeeded(context: Context, networkAvailable: Flow<Unit>) {
        val prefs = DirectBoot.preferences(context, PREFS_NAME)
        val bootCount = readBootCount(context)
        val lastNotified = prefs.getInt(KEY_LAST_NOTIFIED_BOOT, -1).takeIf { it >= 0 }
        if (!shouldSend(bootCount, lastNotified, sentInProcess)) {
            OperationLogger.d(TAG, "Boot notice already sent (boot=$bootCount)")
            return
        }

        var backoffMs = INITIAL_BACKOFF_MS
        while (true) {
            val unlocked = DirectBoot.isUserUnlocked(context)
            try {
                TelegramClient.sendBootNoticeToTelegram(unlocked)
                sentInProcess = true
                bootCount?.let { prefs.edit { putInt(KEY_LAST_NOTIFIED_BOOT, it) } }
                OperationLogger.d(TAG, "Boot notice sent (boot=$bootCount unlocked=$unlocked)")
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                OperationLogger.e(TAG, "Boot notice failed, retrying in ${backoffMs / 1000} s: ${e.message}")
                withTimeoutOrNull(backoffMs) { networkAvailable.first() }
                backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
            }
        }
    }

    private fun readBootCount(context: Context): Int? = runCatching {
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT)
    }.getOrNull()
}
