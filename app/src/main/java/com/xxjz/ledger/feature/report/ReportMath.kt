package com.xxjz.ledger.feature.report

import com.xxjz.ledger.core.common.DateUtils
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.abs

/** 排行的一项（P07 分类排行 / P08 二级分类 / P09 Top3）。 */
data class RankItem(
    val id: String,
    val name: String,
    val amount: Long,
    val count: Int,
    /** 占比，取值 0~100 的整数，保证所有项相加恰好 100。 */
    val percent: Int,
    /** 相对上一周期的变化率（百分比，正=涨、负=跌），null 表示上期为 0 无法比较。 */
    val changePercent: Int? = null,
    /** 子项，用于下钻页。 */
    val children: List<RankItem> = emptyList(),
) {
    val isEmpty: Boolean get() = amount == 0L
}

/** 趋势图上的一个点。 */
data class TrendPoint(
    /** 轴标签，如 `3/05`、`3月`。 */
    val label: String,
    val expense: Long,
    val income: Long,
) {
    val net: Long get() = income - expense
}

/** 报表页聚合结果。 */
data class ReportSummary(
    val expense: Long = 0,
    val income: Long = 0,
    val count: Int = 0,
    val ranks: List<RankItem> = emptyList(),
    val trend: List<TrendPoint> = emptyList(),
    /** 日均（窗口内已过天数）。 */
    val dailyAverage: Long = 0,
    /** 与上一周期的对比，null 表示上期为 0。 */
    val expenseChange: Int? = null,
    val incomeChange: Int? = null,
) {
    val balance: Long get() = income - expense
    val hasData: Boolean get() = expense > 0 || income > 0

    /** 最高支出的一天，用于趋势图点击提示。 */
    val peak: TrendPoint? get() = trend.maxByOrNull { it.expense }?.takeIf { it.expense > 0 }
}

/**
 * 报表纯计算（无 Android 依赖，便于单测）。
 *
 * AC-43 的统计口径一致性在这里集中体现：调用方只传已过滤（未软删、未被标记不计入）的数据。
 */
object ReportMath {
    /**
     * 按占比分配整数百分比，使用**最大余数法**保证总和恰好 100。
     *
     * 直接 `round(x/total*100)` 会出现 33+33+33=99 的窟窿（DEV_PLAN M5 技术点）。
     */
    fun percentAllocation(amounts: List<Long>): List<Int> {
        val total = amounts.sum()
        if (total <= 0) return amounts.map { 0 }
        val exact = amounts.map { it.toDouble() * 100.0 / total }
        val floor = exact.map { kotlin.math.floor(it).toInt() }
        val used = floor.sum()
        val remain = 100 - used
        if (remain == 0) return floor
        // 余数从大到小分配，余数相同时按原顺序（金额大的在前），保证结果稳定
        val order =
            exact
                .mapIndexed { index, value -> index to (value - floor[index]) }
                .sortedWith(compareByDescending<Pair<Int, Double>> { it.second }.thenBy { it.first })
                .map { it.first }
        val result = floor.toMutableList()
        repeat(remain) { i -> result[order[i % order.size]] += 1 }
        return result
    }

    /** 环比/同比变化率：上期为 0 返回 null（无法比较），否则四舍五入到整数百分比。 */
    fun changePercent(
        current: Long,
        previous: Long,
    ): Int? {
        if (previous == 0L) return if (current == 0L) 0 else null
        return Math.round((current - previous) * 100.0 / abs(previous)).toInt()
    }

    /**
     * 构造排行：按金额倒序、占比用最大余数法分配、可附带上一周期金额算出变化率。
     *
     * @param previous 上一周期 `id -> 金额`，用于环比。
     */
    fun buildRanks(
        rows: List<Triple<String, String, Long>>,
        previous: Map<String, Long> = emptyMap(),
        countOf: Map<String, Int> = emptyMap(),
    ): List<RankItem> {
        val sorted = rows.filter { it.third > 0 }.sortedWith(compareByDescending<Triple<String, String, Long>> { it.third })
        val percents = percentAllocation(sorted.map { it.third })
        return sorted.mapIndexed { index, (id, name, amount) ->
            RankItem(
                id = id,
                name = name,
                amount = amount,
                count = countOf[id] ?: 0,
                percent = percents[index],
                changePercent = changePercent(amount, previous[id] ?: 0L),
            )
        }
    }

    /**
     * 把「分类下按天/按月的金额」整理成趋势线；没有数据的点补 0，保证 X 轴连续。
     */
    fun buildTrend(
        labels: List<String>,
        expenseByIndex: Map<Int, Long>,
        incomeByIndex: Map<Int, Long>,
    ): List<TrendPoint> =
        labels.mapIndexed { index, label ->
            TrendPoint(
                label = label,
                expense = expenseByIndex[index] ?: 0L,
                income = incomeByIndex[index] ?: 0L,
            )
        }

    /** 日均：窗口已过天数（含今天）为分母，不足一天按一天。 */
    fun dailyAverage(
        total: Long,
        elapsedDays: Int,
    ): Long = if (elapsedDays <= 0) total else total / elapsedDays

    /**
     * 年度总结：12 个月双柱 + Top3 分类 + Top3 商家 + 结余 + 与去年对比。
     */
    fun yearMonths(
        expenseByMonth: Map<Int, Long>,
        incomeByMonth: Map<Int, Long>,
    ): List<TrendPoint> =
        (1..12).map { m ->
            TrendPoint(
                label = "${m}月",
                expense = expenseByMonth[m] ?: 0L,
                income = incomeByMonth[m] ?: 0L,
            )
        }

    /**
     * 月度环比：**本月至今** 对比 **上月同期**（同一天，短月取月末）。
     *
     * 若拿本月至今和上月全月比，月中看永远是跌的（DEV_PLAN M5 技术点）。
     * `days` 是本月已过天数（含今天），返回上月同期的截止毫秒。
     */
    fun sameSpanEndOfLastMonth(
        today: LocalDate,
        daysInThisMonth: Int,
    ): Long {
        val lastMonth = YearMonth.from(today).minusMonths(1)
        val day = daysInThisMonth.coerceAtMost(lastMonth.lengthOfMonth())
        return DateUtils.toMillis(lastMonth.atDay(day), java.time.LocalTime.MAX)
    }

    /** 取前 n 名，金额为 0 的不参与。 */
    fun topN(
        rows: List<RankItem>,
        n: Int,
    ): List<RankItem> = rows.filter { it.amount > 0 }.sortedByDescending { it.amount }.take(n)
}
