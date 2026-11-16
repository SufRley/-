package com.xxjz.ledger.data.repository

import com.xxjz.ledger.data.local.dao.MetaDao
import com.xxjz.ledger.data.local.dao.MetaEntity
import com.xxjz.ledger.domain.model.AssetEvent
import com.xxjz.ledger.domain.model.AssetEventKind
import com.xxjz.ledger.domain.repository.AssetEventRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 资产变化事件存储（v1.8.0）。
 *
 * 为什么复用 `app_meta` 而不新建表：这类事件只有「改市值 / 改初始余额」两种，
 * 量级极小（用户手动操作次数），且 PRD 明确「不单独建表」。用 KV 前缀存放，
 * 同时避开 Room schema 变更，减少一次迁移风险。
 *
 * 键格式：`asset_event_<13 位时间戳>_<序号>`，按 key 倒序即为时间倒序。
 */
@Singleton
class AssetEventStore
    @Inject
    constructor(
        private val metaDao: MetaDao,
    ) : AssetEventRepository {
        /** 只读写自己写的结构，未知键忽略、默认值照写，便于后续加字段。 */
        private val json =
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            }

        @Serializable
        private data class Row(
            val id: String,
            val at: Long,
            val kind: String,
            val accountId: String? = null,
            val accountName: String = "",
            val deltaCents: Long = 0,
            val amountAfterCents: Long? = null,
        )

        /** 记录一条事件；[seq] 用于同一毫秒内多条事件避免 key 冲突。 */
        override suspend fun record(
            event: AssetEvent,
            seq: Int,
        ) {
            val row =
                Row(
                    id = event.id,
                    at = event.at,
                    kind = event.kind.name,
                    accountId = event.accountId,
                    accountName = event.accountName,
                    deltaCents = event.deltaCents,
                    amountAfterCents = event.amountAfterCents,
                )
            val key = "$PREFIX${event.at}_${seq}_${event.id}"
            metaDao.put(MetaEntity(key, json.encodeToString(Row.serializer(), row)))
        }

        /** 全部事件，按时间倒序。解析失败的脏数据直接跳过，不让一条坏记录拖垮整个报表。 */
        override suspend fun listAll(): List<AssetEvent> = metaDao.listByPrefix(PREFIX).mapNotNull(::parse)

        /** 单条解析：脏数据直接跳过，不让一条坏记录拖垮整个报表。 */
        private fun parse(entity: MetaEntity): AssetEvent? =
            runCatching {
                val row = json.decodeFromString(Row.serializer(), entity.value)
                AssetEvent(
                    id = row.id,
                    at = row.at,
                    kind = runCatching { AssetEventKind.valueOf(row.kind) }.getOrElse { AssetEventKind.MARKET_VALUE },
                    accountId = row.accountId,
                    accountName = row.accountName,
                    deltaCents = row.deltaCents,
                    amountAfterCents = row.amountAfterCents,
                )
            }.getOrNull()

        /** 响应式版本：资产报表订阅用，改市值后列表即时刷新。 */
        override fun observeAll(): Flow<List<AssetEvent>> = metaDao.observeByPrefix(PREFIX).map { it.mapNotNull(::parse) }

        companion object {
            const val PREFIX = "asset_event_"
        }
    }
