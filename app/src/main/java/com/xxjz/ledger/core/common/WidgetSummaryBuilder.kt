package com.xxjz.ledger.core.common

import com.xxjz.ledger.domain.model.Account
import com.xxjz.ledger.domain.model.MonthlySummary
import com.xxjz.ledger.domain.usecase.AssetSummary
import com.xxjz.ledger.domain.usecase.NetAssetsCalculator

/**
 * 把仓库层的两份聚合结果折成小组件要的那几个数。
 *
 * 单独抽成对象而不是塞进 Glance 的 `ActionCallback`/Repository 里，
 * 是因为「净资产怎么算」这件事必须和「我的」页完全一致：
 * 余额为正计资产、为负计负债（AC-19），两处口径不同会让用户觉得数字对不上。
 */
object WidgetSummaryBuilder {
    fun build(
        month: MonthlySummary,
        assets: AssetSummary,
        count: Int = month.count,
    ): WidgetSummary =
        WidgetSummary(
            income = month.income,
            expense = month.expense,
            netAssets = assets.net,
            count = count,
        )

    /** 账户列表 → 净资产（v1.8.0：复用纯计算器，与账户页完全同口径）。 */
    fun netAssetsOf(accounts: List<Account>): Long = NetAssetsCalculator.net(accounts)
}
