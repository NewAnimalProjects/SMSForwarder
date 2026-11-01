package dev.zaplatin.smsforwarder

import android.content.Context
import android.provider.Settings
import androidx.core.content.edit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Tells Telegram that the phone rebooted and waits for the first unlock: until then the platform
 * holds incoming SMS, so nothing is forwarded. Sent at most once per boot.
 */
internal object LockedBootNotice {
    private const val PREFS_NAME = "locked_boot_prefs"
    private const val KEY_LAST_NOTIFIED_BOOT = "last_notified_boot_count"
    private const val INITIAL_BACKOFF_MS = 10_000L
    private const val MAX_BACKOFF_MS = 300_000L
    private const val TAG = "LockedBootNotice"

    internal fun shouldSend(bootCount: Int?, lastNotifiedBootCount: Int?): Boolean =
        bootCount == null || bootCount != lastNotifiedBootCount

    /** Retries until sent or until the user unlocks the phone (then the notice is moot). */
    suspend fun sendIfNeeded(context: Context, networkAvailable: Flow<Unit>) {
        val prefs = DirectBoot.preferences(context, PREFS_NAME)
        val bootCount = readBootCount(context)
        val lastNotified = prefs.getInt(KEY_LAST_NOTIFIED_BOOT, -1).takeIf { it >= 0 }
        if (!shouldSend(bootCount, lastNotified)) return

        var backoffMs = INITIAL_BACKOFF_MS
        while (!DirectBoot.isUserUnlocked(context)) {
            try {
                TelegramClient.sendLockedBootNoticeToTelegram()
                bootCount?.let { prefs.edit { putInt(KEY_LAST_NOTIFIED_BOOT, it) } }
                OperationLogger.d(TAG, "Locked boot notice sent (boot=$bootCount)")
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                OperationLogger.e(TAG, "Locked boot notice failed, retrying in ${backoffMs / 1000} s: ${e.message}")
                withTimeoutOrNull(backoffMs) { networkAvailable.first() }
                backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
            }
        }
        OperationLogger.d(TAG, "Unlocked before the notice was sent; skipping it")
    }

    private fun readBootCount(context: Context): Int? = runCatching {
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT)
    }.getOrNull()
}
