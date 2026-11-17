package com.xxjz.ledger.feature.search

import com.xxjz.ledger.core.common.DateUtils
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** M4 搜索条件换算测试（AC-30）。 */
class SearchFiltersTest {
    private val today = LocalDate.of(2025, 3, 15)

    @Test
    @DisplayName("全部时间不设边界")
    fun allRangeIsUnbounded() {
        val r = SearchFilters.range(SearchRangeOption.ALL, today)
        assertEquals(0L, r.first)
        assertEquals(Long.MAX_VALUE, r.last)
    }

    @Test
    @DisplayName("本月区间覆盖当月首日零点到次月首日前 1 毫秒")
    fun thisMonthRange() {
        val r = SearchFilters.range(SearchRangeOption.THIS_MONTH, today)
        assertEquals(DateUtils.startOfDayMillis(LocalDate.of(2025, 3, 1)), r.first)
        assertEquals(DateUtils.startOfDayMillis(LocalDate.of(2025, 4, 1)) - 1, r.last)
    }

    @Test
    @DisplayName("上月区间跨年正确（1 月的上月是去年 12 月）")
    fun lastMonthRangeCrossYear() {
        val r = SearchFilters.range(SearchRangeOption.LAST_MONTH, LocalDate.of(2025, 1, 10))
        assertEquals(DateUtils.startOfDayMillis(LocalDate.of(2024, 12, 1)), r.first)
        assertEquals(DateUtils.startOfDayMillis(LocalDate.of(2025, 1, 1)) - 1, r.last)
    }

    @Test
    @DisplayName("近 7 天含今天共 7 天")
    fun last7DaysRange() {
        val r = SearchFilters.range(SearchRangeOption.LAST_7_DAYS, today)
        assertEquals(DateUtils.startOfDayMillis(LocalDate.of(2025, 3, 9)), r.first)
        assertEquals(DateUtils.startOfDayMillis(LocalDate.of(2025, 3, 16)) - 1, r.last)
        // 区间内天数正好 7 天
        val days = (r.first..r.last).count { it == r.first || DateUtils.dateKeyOf(it) != DateUtils.dateKeyOf(it - 1) }
        assertEquals(7, days)
    }

    @Test
    @DisplayName("金额下限：空串与非法输入按 0")
    fun minCentsFallback() {
        assertEquals(0L, SearchFilters.minCents(""))
        assertEquals(0L, SearchFilters.minCents("abc"))
        assertEquals(0L, SearchFilters.minCents("."))
    }

    @Test
    @DisplayName("金额上限：空串与非法输入按无上限")
    fun maxCentsFallback() {
        assertEquals(SearchFilters.UNBOUNDED_MAX, SearchFilters.maxCents(""))
        assertEquals(SearchFilters.UNBOUNDED_MAX, SearchFilters.maxCents("abc"))
    }

    @Test
    @DisplayName("元转分：28 元 = 2800 分，28.5 = 2850 分")
    fun yuanToCentsConversion() {
        assertEquals(2800L, SearchFilters.minCents("28"))
        assertEquals(2850L, SearchFilters.maxCents("28.5"))
        assertEquals(2851L, SearchFilters.maxCents("28.51"))
    }

    @Test
    @DisplayName("金额区间合法性：下限大于上限时非法")
    fun amountRangeValidity() {
        assertTrue(SearchFilters.amountRangeValid("", ""))
        assertTrue(SearchFilters.amountRangeValid("10", "20"))
        assertTrue(SearchFilters.amountRangeValid("20", "20"))
        assertFalse(SearchFilters.amountRangeValid("20", "10"))
        assertFalse(SearchFilters.amountRangeValid("20", "19.99"))
    }

    @Test
    @DisplayName("搜索范围选项文案齐全（4 项）")
    fun rangeOptionsLabels() {
        assertEquals(4, SearchRangeOption.entries.size)
        SearchRangeOption.entries.forEach { assertTrue(it.label.isNotBlank()) }
    }
}
