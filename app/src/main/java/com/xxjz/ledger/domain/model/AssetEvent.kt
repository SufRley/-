package com.xxjz.ledger.domain.model

/**
 * 资产变化事件（v1.8.0）。
 *
 * 「资产变化记录」由两路数据合并而成（PRD 6.4）：
 * 1. **流水回算**：收入 / 支出 / 退款 / 坏账 / 删流水 —— 直接来自 `txn` 表；
 * 2. **元数据事件**：改市值 / 改初始余额 —— 这两件事不改流水，只改余额或市值，
 *    因此单独记一条轻量事件，落在 `app_meta` 表（不新建表、不加 schema 版本）。
 *
 * 转账（含投资买入/卖出）一减一增，不改变净资产总额，**两条路都不记录**（PRD D14）。
 */
enum class AssetEventKind {
    /** 调整投资市值。 */
    MARKET_VALUE,

    /** 修改账户初始余额。 */
    INITIAL_BALANCE,
}

data class AssetEvent(
    /** 事件发生时间（毫秒时间戳）。 */
    val at: Long,
    val kind: AssetEventKind,
    /** 关联账户 id，可能为空（账户已彻底删除）。 */
    val accountId: String?,
    /** 账户名快照，账户改名/删除后仍能显示。 */
    val accountName: String,
    /**
     * 事件金额（分），含义随 [kind] 变：
     * - [AssetEventKind.MARKET_VALUE]：本次调整到的市值（PRD D14 的展示口径）；
     * - [AssetEventKind.INITIAL_BALANCE]：修改后的初始余额。
     */
    val deltaCents: Long,
    /** 幂等键：同一事件重复写入时覆盖。 */
    val id: String,
)
