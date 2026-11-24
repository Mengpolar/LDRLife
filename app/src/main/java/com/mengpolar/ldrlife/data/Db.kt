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

/** 消息状态：0=发送中 1=已送达（服务器确认收取）；已读/❗过期在 M1 扩展 */
const val MSG_SENDING = 0
const val MSG_SENT = 1

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val seq: Long,
    val outgoing: Boolean,
    val body: String,
    val ts: Long,
    val status: Int,
)

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages ORDER BY id ASC")
    fun observeAll(): Flow<List<MessageEntity>>

    @Insert
    suspend fun insert(m: MessageEntity): Long

    @Query("UPDATE messages SET status=:status WHERE id=:id")
    suspend fun updateStatus(id: Long, status: Int)

    @Query("DELETE FROM messages")
    suspend fun clear()
}

@Database(entities = [MessageEntity::class], version = 1, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun messages(): MessageDao
}

/**
 * SQLCipher 加密本地库：库密钥随机生成一次，经 AndroidKeyStore AES-GCM 包裹存偏好。
 * 聊天记录仅存本机，无云端备份。
 */
object DbFactory {

    fun create(context: Context, prefs: SharedPreferences): AppDb {
        System.loadLibrary("sqlcipher")
        val passphrase = dbPassphrase(prefs)
        return Room.databaseBuilder(context, AppDb::class.java, "ldr.db")
            .openHelperFactory(SupportOpenHelperFactory(passphrase))
            .fallbackToDestructiveMigration()
            .build()
    }

    private fun dbPassphrase(prefs: SharedPreferences): ByteArray {
        val stored = prefs.getString("db_key_wrapped", null)
        if (stored != null) {
            return try {
                KeystoreBox.unwrap(stored)
            } catch (_: Exception) {
                regenerate(prefs)
            }
        }
        return regenerate(prefs)
    }

    private fun regenerate(prefs: SharedPreferences): ByteArray {
        val key = KeystoreBox.randomToken(32)
        prefs.edit().putString("db_key_wrapped", KeystoreBox.wrap(key)).apply()
        return key
    }
}
