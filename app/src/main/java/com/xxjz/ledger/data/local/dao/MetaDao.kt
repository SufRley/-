package com.xxjz.ledger.data.local.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import com.xxjz.ledger.data.local.entity.LedgerEntity
import kotlinx.coroutines.flow.Flow

/** 简易键值元数据表，记录种子版本等。 */
@Entity(tableName = "app_meta")
data class MetaEntity(
    @PrimaryKey val key: String,
    val value: String,
)

/** 键值元数据访问。 */
@Dao
interface MetaDao {
    @Query("SELECT value FROM app_meta WHERE `key` = :key")
    suspend fun get(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(meta: MetaEntity)

    /** 按前缀取全部键值（v1.8.0：资产变化事件以 `asset_event_<ts>_<seq>` 为键批量读取）。 */
    @Query("SELECT * FROM app_meta WHERE `key` LIKE :prefix || '%' ORDER BY `key` DESC")
    suspend fun listByPrefix(prefix: String): List<MetaEntity>

    /** [listByPrefix] 的响应式版本，供资产报表订阅变化。 */
    @Query("SELECT * FROM app_meta WHERE `key` LIKE :prefix || '%' ORDER BY `key` DESC")
    fun observeByPrefix(prefix: String): Flow<List<MetaEntity>>
}

/** 账本（多账本预留，v1 只有默认账本）。 */
@Dao
interface LedgerDao {
    @Query("SELECT * FROM ledger ORDER BY isDefault DESC LIMIT 1")
    suspend fun defaultLedger(): LedgerEntity?

    @Query("SELECT * FROM ledger ORDER BY isDefault DESC")
    suspend fun listAll(): List<LedgerEntity>

    @Upsert
    suspend fun upsert(ledger: LedgerEntity)
}

/** 聚合查询的通用列形状：key 可能是分类 id / 账户 id / 类型名 / 日期键（以字符串承载）。 */
data class MoneyBucket(
    @ColumnInfo(name = "key") val key: String?,
    @ColumnInfo(name = "total") val total: Long,
    @ColumnInfo(name = "cnt") val cnt: Int,
)
