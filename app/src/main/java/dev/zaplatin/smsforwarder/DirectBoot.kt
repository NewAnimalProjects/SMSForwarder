package dev.zaplatin.smsforwarder

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.ContextCompat
import androidx.core.os.UserManagerCompat

/**
 * Helpers for running before the first unlock after a reboot (Direct Boot). Until then only
 * device-protected storage is readable; Room, WorkManager and default SharedPreferences are not.
 */
internal object DirectBoot {
    private const val TAG = "DirectBoot"
    private val migrated = mutableSetOf<String>()

    fun isUserUnlocked(context: Context): Boolean = UserManagerCompat.isUserUnlocked(context)

    /**
     * SharedPreferences in device-protected storage, readable before the first unlock.
     * The first access after unlock moves the old credential-protected file over, once.
     */
    fun preferences(context: Context, name: String): SharedPreferences {
        val appContext = context.applicationContext
        val deviceContext = ContextCompat.createDeviceProtectedStorageContext(appContext) ?: appContext
        if (deviceContext !== appContext && isUserUnlocked(appContext)) {
            migrateOnce(appContext, deviceContext, name)
        }
        return deviceContext.getSharedPreferences(name, Context.MODE_PRIVATE)
    }

    private fun migrateOnce(source: Context, target: Context, name: String) = synchronized(migrated) {
        if (!migrated.add(name)) return
        if (target.getSharedPreferences(name, Context.MODE_PRIVATE).all.isNotEmpty()) {
            // Written while locked (e.g. first boot after an app update): newer than the old copy.
            source.deleteSharedPreferences(name)
            return
        }
        if (target.moveSharedPreferencesFrom(source, name)) {
            OperationLogger.d(TAG, "Prefs '$name' are in device-protected storage")
        } else {
            OperationLogger.e(TAG, "Failed to move prefs '$name' to device-protected storage")
        }
    }
}
