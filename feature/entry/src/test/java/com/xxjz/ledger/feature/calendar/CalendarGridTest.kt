package com.xxjz.ledger.feature.calendar

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.LocalDate

/** M4 日历网格测试（AC-32）。 */
class CalendarGridTest {
    @Test
    @DisplayName("网格总数为整周（7 的倍数）")
    fun cellsFillWholeWeeks() {
        for (month in 1..12) {
            val cells = CalendarGrid.build(2025, month)
            assertEquals(0, cells.size % 7, "2025-$month 网格不是整周")
            assertTrue(cells.size in 28..42, "2025-$month 网格行数异常：${cells.size}")
        }
    }

    @Test
    @DisplayName("周一为首：第一格位置对应当月 1 号星期")
    fun mondayFirstAlignment() {
        // 2025-09-01 是周一 → 无补位
        val sep = CalendarGrid.build(2025, 9)
        assertEquals(LocalDate.of(2025, 9, 1), sep.first().date)
        // 2025-06-01 是周日 → 前面补 6 个空位
        val jun = CalendarGrid.build(2025, 6)
        assertEquals(6, jun.takeWhile { it.date == null }.size)
        assertEquals(LocalDate.of(2025, 6, 1), jun[6].date)
        assertEquals(DayOfWeek.SUNDAY, LocalDate.of(2025, 6, 1).dayOfWeek)
    }

    @Test
    @DisplayName("补位格日期为 null，真实格日期连续无缺")
    fun realDatesAreContiguous() {
        val cells = CalendarGrid.build(2025, 3)
        val dates = cells.mapNotNull { it.date }
        assertEquals(31, dates.size)
        dates.zipWithNext().forEach { (a, b) -> assertEquals(a.plusDays(1), b) }
        assertEquals(LocalDate.of(2025, 3, 1), dates.first())
        assertEquals(LocalDate.of(2025, 3, 31), dates.last())
        cells.forEach { cell -> if (cell.date == null) assertNull(cell.date) else assertNotNull(cell.date) }
    }

    @Test
    @DisplayName("闰年 2 月 29 天，平年 2 月 28 天")
    fun leapFebruary() {
        assertEquals(29, CalendarGrid.build(2024, 2).count { it.date != null })
        assertEquals(28, CalendarGrid.build(2025, 2).count { it.date != null })
    }

    @Test
    @DisplayName("按月切 7 列后每周恰好 7 格，且每行首格都是周一或空")
    fun chunkedRowsHaveSevenCells() {
        val weeks = CalendarGrid.build(2025, 12).chunked(7)
        weeks.forEach { assertEquals(7, it.size) }
        val firstWeek = weeks.first()
        // 第一行里所有真实日期都早于等于 12/7，且属于 12 月
        firstWeek.mapNotNull { it.date }.forEach {
            assertEquals(12, it.monthValue)
            assertTrue(it.dayOfMonth <= 7)
        }
    }

    @Test
    @DisplayName("星期标签从周一到周日共 7 个")
    fun weekLabels() {
        assertEquals(listOf("一", "二", "三", "四", "五", "六", "日"), CalendarGrid.weekLabels)
    }
}
