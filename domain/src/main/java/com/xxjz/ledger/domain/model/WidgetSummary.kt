package com.xxjz.ledger.domain.model

import com.xxjz.ledger.core.common.MoneyFormatter

/**
 * 首页小组件要展示的数字（AC-83）。
 *
 * 做成纯数据 + 纯函数，Glance 那一层只负责把文字画出来：
 * 排版错了是 UI 问题，算法错了是这里的问题，单元测试能直接盯住后者。
 */
data class WidgetSummary(
    /** 本月收入（分）。 */
    val income: Long = 0,
    /** 本月支出（分）。 */
    val expense: Long = 0,
    /** 净资产（分），可正可负。 */
    val netAssets: Long = 0,
    /** 本月已记账笔数。 */
    val count: Int = 0,
) {
    /** 本月结余 = 收入 - 支出。 */
    val balance: Long get() = income - expense

    /** 一笔没记时，小组件改成引导文案而不是一排 0。 */
    val isEmpty: Boolean get() = count == 0 && income == 0L && expense == 0L
}

/** 小组件上的一行三列的文案，直接交给 `Text` 用。 */
data class WidgetLines(
    val title: String,
    val expenseText: String,
    val incomeText: String,
    val balanceText: String,
)

/**
 * 小组件文案生成。
 *
 * 金额用「元」展示并保留两位小数；带负号时符号放在最前面（`-¥12.00` 而不是 `¥-12.00`），
 * 这是列表页与报表页统一的写法，小组件不能自创一套。
 */
object WidgetFormatter {
    fun lines(
        summary: WidgetSummary,
        monthLabel: String,
    ): WidgetLines {
        val title = if (summary.isEmpty) "$monthLabel · 还没记账" else "$monthLabel · 记了 ${summary.count} 笔"
        return WidgetLines(
            title = title,
            expenseText = yuan(summary.expense),
            incomeText = yuan(summary.income),
            balanceText = yuan(summary.balance),
        )
    }

    /** 分 → `¥1,234.56`；负数符号前置。 */
    fun yuan(cents: Long): String = MoneyFormatter.centsToYuan(cents, showSymbol = true)
}
