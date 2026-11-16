package com.xxjz.ledger.domain.repository

import com.xxjz.ledger.domain.model.AssetEvent
import kotlinx.coroutines.flow.Flow

/**
 * 资产变化事件（改市值 / 改初始余额）的读写契约（v1.8.0）。
 *
 * 实现复用 `app_meta` KV 表（量级极小，PRD 明确「不单独建表」），
 * 细节见 data 层的 `AssetEventStore`。
 */
interface AssetEventRepository {
    /** 记录一条事件；[seq] 用于同一毫秒内多条事件避免 key 冲突。 */
    suspend fun record(
        event: AssetEvent,
        seq: Int = 0,
    )

    /** 全部事件，按时间倒序；解析失败的脏数据直接跳过。 */
    suspend fun listAll(): List<AssetEvent>

    /** 响应式版本：资产报表订阅用，改市值后列表即时刷新。 */
    fun observeAll(): Flow<List<AssetEvent>>
}
