package com.xxjz.ledger.core.common

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDate

@DisplayName("DateUtils 日期工具")
class DateUtilsTest {
    @Test
    fun `dateKey 生成与还原`() {
        assertEquals(20250101, DateUtils.dateKeyOf(LocalDate.of(2025, 1, 1)))
        assertEquals(20251231, DateUtils.dateKeyOf(LocalDate.of(2025, 12, 31)))
        assertEquals(LocalDate.of(2025, 6, 9), DateUtils.dateKeyToDate(20250609))
    }

    @Test
    fun `友好日期`() {
        val today = LocalDate.of(2025, 3, 10)
        assertEquals("今天", DateUtils.friendlyDate(today, today))
        assertEquals("昨天", DateUtils.friendlyDate(today.minusDays(1), today))
        assertEquals("前天", DateUtils.friendlyDate(today.minusDays(2), today))
        assertEquals("3月7日", DateUtils.friendlyDate(today.minusDays(3), today))
    }

    @Test
    fun `周期推算跨月`() {
        assertEquals(LocalDate.of(2025, 2, 28), DateUtils.plusPeriod(LocalDate.of(2025, 1, 31), "MONTH"))
        assertEquals(LocalDate.of(2025, 3, 1), DateUtils.plusPeriod(LocalDate.of(2025, 2, 28), "DAY"))
        assertEquals(LocalDate.of(2025, 3, 3), DateUtils.plusPeriod(LocalDate.of(2025, 2, 24), "WEEK"))
    }

    @Test
    fun `周期推算跨年`() {
        assertEquals(LocalDate.of(2026, 1, 15), DateUtils.plusPeriod(LocalDate.of(2025, 12, 15), "MONTH"))
        assertEquals(LocalDate.of(2026, 12, 31), DateUtils.plusPeriod(LocalDate.of(2025, 12, 31), "YEAR"))
        assertEquals(LocalDate.of(2025, 12, 31), DateUtils.plusPeriod(LocalDate.of(2025, 12, 31), "DAY", 0))
    }

    @Test
    fun `2月29日按年周期`() {
        val leap = LocalDate.of(2024, 2, 29)
        assertEquals(LocalDate.of(2025, 2, 28), DateUtils.plusPeriod(leap, "YEAR"))
        assertEquals(LocalDate.of(2024, 3, 29), DateUtils.plusPeriod(leap, "MONTH"))
        assertEquals(29, DateUtils.daysInMonth(2024, 2))
        assertEquals(28, DateUtils.daysInMonth(2025, 2))
    }

    @Test
    fun `当月剩余天数含今天`() {
        assertEquals(31, DateUtils.remainingDaysInMonth(LocalDate.of(2025, 1, 1)))
        assertEquals(1, DateUtils.remainingDaysInMonth(LocalDate.of(2025, 1, 31)))
        assertEquals(28, DateUtils.remainingDaysInMonth(LocalDate.of(2025, 2, 1)))
        assertEquals(1, DateUtils.elapsedDaysInMonth(LocalDate.of(2025, 2, 1)))
    }

    @Test
    fun `月份区间覆盖整月`() {
        val range = DateUtils.monthRange(2025, 2)
        assertEquals(DateUtils.startOfDayMillis(LocalDate.of(2025, 2, 1)), range.first)
        assertEquals(DateUtils.startOfDayMillis(LocalDate.of(2025, 3, 1)) - 1, range.last)
    }

    @Test
    fun `月份键`() {
        assertEquals(202503, DateUtils.monthKeyOf(LocalDate.of(2025, 3, 9)))
        assertEquals(2025, DateUtils.yearOf(202503))
        assertEquals(3, DateUtils.monthOf(202503))
    }
}
