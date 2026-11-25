package com.mengpolar.ldrlife.data

import android.content.Context
import android.content.SharedPreferences
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import com.mengpolar.ldrlife.core.KeystoreBox
import kotlinx.coroutines.flow.Flow
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/** 消息状态机（设计文档 §5.1）：
 *  0 发送中 → 1 已送达(服务器确认缓存) → 2 已送达✓✓(对方取走) → 3 已读
 *  任何未达状态超 7 天 → 4 ❗过期（可重发） */
const val MSG_SENDING = 0
const val MSG_SENT = 1
const val MSG_DELIVERED = 2
const val MSG_READ = 3
const val MSG_EXPIRED = 4

const val TTL_MS = 7L * 24 * 3600 * 1000

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val seq: Long,
    val outgoing: Boolean,
    val body: String,
    val ts: Long,
    val status: Int,
    /** 已封装的密文（重发队列用：重发同一密文，避免换 nonce 重复加密） */
    val wire: String? = null,
)

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages ORDER BY id ASC")
    fun observeAll(): Flow<List<MessageEntity>>

    @Insert
    suspend fun insert(m: MessageEntity): Long

    @Query("UPDATE messages SET status=:status WHERE id=:id")
    suspend fun updateStatus(id: Long, status: Int)

    @Query("SELECT * FROM messages WHERE outgoing=1 AND status=:status")
    suspend fun byStatus(status: Int): List<MessageEntity>

    @Query("UPDATE messages SET status=:newStatus WHERE outgoing=1 AND seq IN (:seqs) AND status<:newStatus")
    suspend fun upgradeBySeqs(seqs: List<Long>, newStatus: Int)

    @Query("UPDATE messages SET status=4 WHERE outgoing=1 AND status<2 AND ts<:cutoff")
    suspend fun markExpired(cutoff: Long)

    @Query("SELECT seq FROM messages WHERE outgoing=0 AND status<3")
    suspend fun unreadIncomingSeqs(): List<Long>

    @Query("DELETE FROM messages")
    suspend fun clear()
}

@Database(entities = [MessageEntity::class], version = 2, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun messages(): MessageDao
}

/**
 * SQLCipher 加密本地库：库密钥随机生成一次，存 AndroidKeyStore（有 StrongBox 优先硬件级）。
 * 聊天记录仅存本机，无云端备份。
 */
object DbFactory {

    fun create(context: Context, prefs: SharedPreferences): AppDb {
        System.loadLibrary("sqlcipher")
        val passphrase = dbPassphrase(context, prefs)
        return Room.databaseBuilder(context, AppDb::class.java, "ldr.db")
            .openHelperFactory(SupportOpenHelperFactory(passphrase))
            .fallbackToDestructiveMigration()
            .build()
    }

    private fun dbPassphrase(context: Context, prefs: SharedPreferences): ByteArray {
        val stored = prefs.getString("db_key_wrapped", null)
        if (stored != null) {
            return try {
                KeystoreBox.unwrap(stored)
            } catch (_: Exception) {
                // Keystore 密钥丢失（系统重置/ROM 异常等）：旧库已无法解密，
                // 必须先删除库文件再生成新密钥，否则打开即 "file is not a database" 崩溃
                deleteDbFiles(context)
                regenerate(prefs)
            }
        }
        return regenerate(prefs)
    }

    private fun deleteDbFiles(context: Context) {
        val db = context.getDatabasePath("ldr.db")
        listOf(
            db,
            java.io.File(db.parentFile, "ldr.db-wal"),
            java.io.File(db.parentFile, "ldr.db-shm"),
            java.io.File(db.parentFile, "ldr.db-journal"),
        ).forEach { it.delete() }
    }

    private fun regenerate(prefs: SharedPreferences): ByteArray {
        val key = KeystoreBox.randomToken(32)
        prefs.edit().putString("db_key_wrapped", KeystoreBox.wrap(key)).apply()
        return key
    }
}
