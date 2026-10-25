package dev.zaplatin.smsforwarder

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface SmsDao {
    @Insert
    suspend fun insert(message: SmsMessageEntity): Long

    @Query("SELECT * FROM sms_messages WHERE id = :id")
    suspend fun getById(id: Long): SmsMessageEntity?

    @Query("SELECT * FROM sms_messages ORDER BY created_at DESC LIMIT :limit")
    suspend fun getLatest(limit: Int): List<SmsMessageEntity>

    @Query(
        "SELECT * FROM sms_messages WHERE status != :sentStatus AND created_at >= :minCreatedAt ORDER BY created_at ASC LIMIT :limit"
    )
    suspend fun getAllNotSent(
        sentStatus: String = SmsStatus.SENT,
        minCreatedAt: Long,
        limit: Int
    ): List<SmsMessageEntity>

    @Query("SELECT COUNT(*) FROM sms_messages")
    suspend fun countAll(): Int

    @Query("SELECT COUNT(*) FROM sms_messages WHERE status != :sentStatus")
    suspend fun countNotSent(sentStatus: String = SmsStatus.SENT): Int

    @Query("UPDATE sms_messages SET status = :sentStatus, last_error = NULL WHERE id = :id")
    suspend fun markSent(id: Long, sentStatus: String = SmsStatus.SENT)

    @Query("UPDATE sms_messages SET status = :failedStatus, last_error = :error WHERE id = :id")
    suspend fun markFailed(id: Long, error: String?, failedStatus: String = SmsStatus.FAILED)
}
