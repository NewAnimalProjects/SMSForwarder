package dev.zaplatin.smsforwarder

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sms_messages")
data class SmsMessageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "from")
    val from: String,
    @ColumnInfo(name = "body")
    val body: String,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "status")
    val status: String,
    @ColumnInfo(name = "destination")
    val destination: String? = null,
    @ColumnInfo(name = "last_error")
    val lastError: String?,
    /** Comma-separated chat ids that already got this SMS; retries skip them. */
    @ColumnInfo(name = "delivered_chats", defaultValue = "")
    val deliveredChats: String = "",
) {
    fun deliveredChatIds(): Set<String> =
        deliveredChats.split(',').filter { it.isNotEmpty() }.toSet()
}

object SmsStatus {
    const val PENDING = "PENDING"
    const val SENT = "SENT"
    const val FAILED = "FAILED"
}
