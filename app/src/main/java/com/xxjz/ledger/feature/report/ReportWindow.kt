package com.xxjz.ledger.feature.report

import com.xxjz.ledger.core.common.DateUtils
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

/** 报表时间窗口：锚点日期 + 粒度，可前后翻页。 */
data class ReportWindow(
    val anchor: LocalDate,
    val period: ReportPeriod,
) {
    /** 窗口起止毫秒（闭区间）。 */
    val range: LongRange
        get() =
            when (period) {
                ReportPeriod.WEEK -> DateUtils.weekRange(anchor)
                ReportPeriod.MONTH -> DateUtils.monthRange(anchor.year, anchor.monthValue)
                ReportPeriod.YEAR -> DateUtils.yearRange(anchor.year)
            }

    /** 窗口标题：`2025年第29周` / `2025年3月` / `2025年`。 */
    val label: String
        get() =
            when (period) {
                ReportPeriod.WEEK -> {
                    val monday = ReportWindowMath.mondayOf(anchor)
                    "${monday.year}年第${monday.get(java.time.temporal.WeekFields.ISO.weekOfWeekBasedYear())}周"
                }
                ReportPeriod.MONTH -> "${anchor.year}年${anchor.monthValue}月"
                ReportPeriod.YEAR -> "${anchor.year}年"
            }

    /** 窗口内每一天（周 7 天、月 28~31 天、年不展开）。 */
    val days: List<LocalDate>
        get() =
            when (period) {
                ReportPeriod.WEEK -> (0L..6L).map { ReportWindowMath.mondayOf(anchor).plusDays(it) }
                ReportPeriod.MONTH -> (1..anchor.lengthOfMonth()).map { LocalDate.of(anchor.year, anchor.monthValue, it) }
                ReportPeriod.YEAR -> emptyList()
            }

    /** 按月展开（仅年视图用）。 */
    val months: List<YearMonth>
        get() = if (period == ReportPeriod.YEAR) (1..12).map { YearMonth.of(anchor.year, it) } else emptyList()

    /**
     * 上一个月/周/年的窗口；粒度切换时保持锚点落在当前窗口内。
     */
    fun shift(step: Int): ReportWindow =
        ReportWindow(
            anchor =
                when (period) {
                    ReportPeriod.WEEK -> ReportWindowMath.mondayOf(anchor).plusWeeks(step.toLong()).plusDays(3)
                    ReportPeriod.MONTH -> anchor.plusMonths(step.toLong())
                    ReportPeriod.YEAR -> anchor.plusYears(step.toLong())
                },
            period = period,
        )

    /** 切换粒度：以当前窗口内的某天为锚点重算。 */
    fun withPeriod(next: ReportPeriod): ReportWindow = ReportWindow(anchor, next)

    /** 去年同期窗口（年度总结「与去年对比」）。 */
    fun lastYearSameSpan(): ReportWindow = ReportWindow(anchor.minusYears(1), period)
}

/** 窗口推算的小工具，单独抽出来便于单测。 */
object ReportWindowMath {
    /** 所在周的周一。 */
    fun mondayOf(date: LocalDate): LocalDate = date.minusDays(((date.dayOfWeek.value + 6) % 7).toLong())

    /**
     * 同比 / 环比文案所需的「同期截止日」。
     *
     * 本月至今 vs 上月同期：若上月没有该日（比如 3/31 对 2 月），取上月最后一天。
     */
    fun sameDayIn(
        targetMonth: YearMonth,
        day: Int,
    ): LocalDate = targetMonth.atDay(day.coerceAtMost(targetMonth.lengthOfMonth()))

    val ymFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM")
}
