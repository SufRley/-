package com.xxjz.ledger.data.local.db

import androidx.room.TypeConverter
import com.xxjz.ledger.core.common.Logger
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Room 类型转换器。
 *
 * - 枚举 ↔ String：各枚举统一按 `name` 存字符串，保证老版本数据可读、SQL 可读。
 * - `List<String>` ↔ JSON：标签 id 列表等集合字段，用 kotlinx.serialization 序列化（不手写解析）。
 */
object Converters {
    private const val TAG = "Converters"

    private val json = Json { ignoreUnknownKeys = true }

    @TypeConverter
    @JvmStatic
    fun fromStringList(value: List<String>?): String = json.encodeToString(value ?: emptyList())

    @TypeConverter
    @JvmStatic
    fun toStringList(value: String?): List<String> =
        if (value.isNullOrBlank()) {
            emptyList()
        } else {
            try {
                json.decodeFromString<List<String>>(value)
            } catch (e: SerializationException) {
                // 脏数据不该让整张表读不出来：解析失败按空列表处理，但要留下现场
                Logger.w(TAG, "标签列表解析失败，按空处理: $value", e)
                emptyList()
            }
        }
}
