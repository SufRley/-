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
     * 本次事件对**净资产**的影响（分），正数增加、负数减少。
     *
     * - [AssetEventKind.MARKET_VALUE]：新市值 − 旧市值（差额，不是新市值本身）；
     * - [AssetEventKind.INITIAL_BALANCE]：新初始余额 − 旧初始余额。
     *
     * 统一存「差额」而不是「新值」，是因为资产报表的净资产回放要按差额累加；
     * 用户界面上要显示的「调整到 xx 元」由 [amountAfterCents] 承载。
     */
    val deltaCents: Long,
    /**
     * 事件发生后的目标值（分）：改市值为新市值、改初始余额为新初始余额。
     *
     * 为 null 表示是 v1.8.0 早期写入的旧记录（只有 deltaCents），展示时退化为显示差额。
     */
    val amountAfterCents: Long? = null,
    /** 幂等键：同一事件重复写入时覆盖。 */
    val id: String,
)
