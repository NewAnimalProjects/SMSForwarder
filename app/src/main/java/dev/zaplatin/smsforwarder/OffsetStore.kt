package dev.zaplatin.smsforwarder

import android.content.Context

/** Next getUpdates offset; survives process restarts so commands are not handled twice. */
interface OffsetStore {
    var offset: Long?
}

/** Device-protected, so bot commands keep working before the first unlock after a reboot. */
class SharedPreferencesOffsetStore(context: Context) : OffsetStore {
    private val prefs = DirectBoot.preferences(context, PREFS_NAME)

    override var offset: Long?
        get() = prefs.getLong(KEY_OFFSET, NO_OFFSET).takeIf { it != NO_OFFSET }
        set(value) {
            prefs.edit().putLong(KEY_OFFSET, value ?: NO_OFFSET).apply()
        }

    private companion object {
        const val PREFS_NAME = "bot_prefs"
        const val KEY_OFFSET = "update_offset"
        const val NO_OFFSET = -1L
    }
}
