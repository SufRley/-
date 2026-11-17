package com.xxjz.ledger.feature.report

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** M5 报表纯计算（AC-37 占比、AC-39 环比、AC-40 趋势、AC-42 年度）。 */
@DisplayName("报表计算")
class ReportMathTest {
    @Test
    @DisplayName("占比之和恰好 100%（33/33/33 的场景）")
    fun percentAlwaysSumsTo100() {
        val percent = ReportMath.percentAllocation(listOf(1000L, 1000L, 1000L))
        assertEquals(100, percent.sum())
        // 最大余数法：三项相等，前两项补 1
        assertEquals(listOf(34, 33, 33), percent)
    }

    @Test
    @DisplayName("大量小项也不会丢百分比")
    fun percentManySmallItems() {
        val amounts = List(7) { 1L } + listOf(93L)
        val percent = ReportMath.percentAllocation(amounts)
        assertEquals(100, percent.sum())
        assertEquals(93, percent.last())
    }

    @Test
    @DisplayName("总金额为 0 时全部占比为 0")
    fun percentZeroTotal() {
        assertEquals(listOf(0, 0, 0), ReportMath.percentAllocation(listOf(0L, 0L, 0L)))
        assertEquals(emptyList<Int>(), ReportMath.percentAllocation(emptyList()))
    }

    @Test
    @DisplayName("占比与原金额顺序一一对应")
    fun percentKeepsOrder() {
        val percent = ReportMath.percentAllocation(listOf(5000L, 3000L, 2000L))
        assertEquals(listOf(50, 30, 20), percent)
    }

    @Test
    @DisplayName("环比：上期为 0 时无法比较返回 null，两期都为 0 返回 0")
    fun changePercentEdgeCases() {
        assertNull(ReportMath.changePercent(1000L, 0L))
        assertEquals(0, ReportMath.changePercent(0L, 0L))
        assertEquals(50, ReportMath.changePercent(1500L, 1000L))
        assertEquals(-50, ReportMath.changePercent(500L, 1000L))
        assertEquals(-100, ReportMath.changePercent(0L, 1000L))
    }

    @Test
    @DisplayName("排行按金额倒序、零金额被剔除、变化率可用")
    fun buildRanksSortsAndFilters() {
        val ranks =
            ReportMath.buildRanks(
                rows =
                    listOf(
                        Triple("food", "餐饮", 3000L),
                        Triple("traffic", "交通", 5000L),
                        Triple("empty", "空分类", 0L),
                    ),
                previous = mapOf("food" to 2000L, "traffic" to 5000L),
                countOf = mapOf("food" to 8),
            )
        assertEquals(listOf("交通", "餐饮"), ranks.map { it.name })
        assertEquals(listOf(63, 37), ranks.map { it.percent })
        assertEquals(0, ranks[0].changePercent)
        assertEquals(50, ranks[1].changePercent)
        assertEquals(8, ranks[1].count)
    }

    @Test
    @DisplayName("趋势补 0：没有数据的日期也占位，X 轴连续")
    fun trendFillsGaps() {
        val trend =
            ReportMath.buildTrend(
                labels = listOf("3/1", "3/2", "3/3"),
                expenseByIndex = mapOf(0 to 100L, 2 to 300L),
                incomeByIndex = mapOf(1 to 50L),
            )
        assertEquals(3, trend.size)
        assertEquals(listOf(100L, 0L, 300L), trend.map { it.expense })
        assertEquals(listOf(0L, 50L, 0L), trend.map { it.income })
        assertEquals(50L, trend[1].net)
    }

    @Test
    @DisplayName("日均按已过天数计算，天数为 0 时退化为总额")
    fun dailyAverage() {
        assertEquals(100L, ReportMath.dailyAverage(1500L, 15))
        assertEquals(1500L, ReportMath.dailyAverage(1500L, 0))
    }

    @Test
    @DisplayName("年度 12 个月双柱，缺月补 0")
    fun yearMonths() {
        val months = ReportMath.yearMonths(mapOf(3 to 1000L), mapOf(3 to 5000L))
        assertEquals(12, months.size)
        assertEquals("1月", months.first().label)
        assertEquals("12月", months.last().label)
        assertEquals(1000L, months[2].expense)
        assertEquals(5000L, months[2].income)
        assertEquals(0L, months[0].expense)
    }

    @Test
    @DisplayName("TopN 只取有金额的前 n 名")
    fun topN() {
        val ranks =
            listOf(
                RankItem("a", "A", 100, 1, 10),
                RankItem("b", "B", 300, 1, 30),
                RankItem("c", "C", 200, 1, 20),
                RankItem("d", "D", 0, 0, 0),
            )
        val top = ReportMath.topN(ranks, 3)
        assertEquals(listOf("B", "C", "A"), top.map { it.name })
        assertEquals(2, ReportMath.topN(ranks, 2).size)
    }

    @Test
    @DisplayName("月度环比：本月至今对比上月同期，短月取月末")
    fun sameSpanEndOfLastMonth() {
        // 3 月已过 31 天 → 对比 2 月 28 日（2025 非闰年）
        val end = ReportMath.sameSpanEndOfLastMonth(LocalDate.of(2025, 3, 31), 31)
        val endDate = com.xxjz.ledger.core.common.DateUtils.toDate(end)
        assertEquals(LocalDate.of(2025, 2, 28), endDate)

        // 3 月已过 15 天 → 对比 2 月 15 日
        val end2 = ReportMath.sameSpanEndOfLastMonth(LocalDate.of(2025, 3, 15), 15)
        assertEquals(LocalDate.of(2025, 2, 15), com.xxjz.ledger.core.common.DateUtils.toDate(end2))
    }

    @Test
    @DisplayName("报表窗口：周/月/年范围与标题")
    fun windowRanges() {
        val day = LocalDate.of(2025, 6, 4) // 周三
        val week = ReportWindow(day, ReportPeriod.WEEK)
        assertEquals(7, week.days.size)
        assertEquals(LocalDate.of(2025, 6, 2), week.days.first()) // 周一
        assertEquals(LocalDate.of(2025, 6, 8), week.days.last())

        val month = ReportWindow(day, ReportPeriod.MONTH)
        assertEquals("2025年6月", month.label)
        assertEquals(30, month.days.size)

        val year = ReportWindow(day, ReportPeriod.YEAR)
        assertEquals("2025年", year.label)
        assertEquals(12, year.months.size)
    }

    @Test
    @DisplayName("翻页：月 ±1、周 ±7 天、年 ±1 年")
    fun windowShift() {
        val day = LocalDate.of(2025, 1, 15)
        assertEquals(LocalDate.of(2025, 2, 15), ReportWindow(day, ReportPeriod.MONTH).shift(1).anchor)
        assertEquals(LocalDate.of(2024, 12, 15), ReportWindow(day, ReportPeriod.MONTH).shift(-1).anchor)
        assertEquals(LocalDate.of(2026, 1, 15), ReportWindow(day, ReportPeriod.YEAR).shift(1).anchor)

        val week = ReportWindow(day, ReportPeriod.WEEK)
        // 一周 = 7 天 = 604_800_000 毫秒
        assertEquals(7L * 24 * 60 * 60 * 1000, week.shift(1).range.first - week.range.first)
    }

    @Test
    @DisplayName("年视图趋势按月 12 个点")
    fun yearTrendPoints() {
        val w = ReportWindow(LocalDate.of(2025, 5, 1), ReportPeriod.YEAR)
        assertTrue(w.range.first < w.range.last)
        assertEquals(LocalDate.of(2025, 1, 1), com.xxjz.ledger.core.common.DateUtils.toDate(w.range.first))
    }
}
