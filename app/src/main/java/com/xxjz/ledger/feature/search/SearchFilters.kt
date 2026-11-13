package com.xxjz.ledger.feature.search

import com.xxjz.ledger.core.common.DateUtils
import com.xxjz.ledger.core.common.MoneyFormatter
import java.time.LocalDate

/** 搜索时间范围快捷项（AC-30）。 */
enum class SearchRangeOption(val label: String) {
    ALL("全部时间"),
    THIS_MONTH("本月"),
    LAST_MONTH("上月"),
    LAST_7_DAYS("近 7 天"),
}

/**
 * 搜索条件换算（纯函数，便于单测）。
 *
 * 金额输入是「元」，库里是「分」，边界统一在这里换算，避免把 `28` 直接和 `2800` 比（DEV_PLAN M4 技术点）。
 */
object SearchFilters {
    const val UNBOUNDED_MAX: Long = Long.MAX_VALUE

    fun range(
        option: SearchRangeOption,
        today: LocalDate = DateUtils.today(),
    ): LongRange =
        when (option) {
            SearchRangeOption.ALL -> 0L..Long.MAX_VALUE
            SearchRangeOption.THIS_MONTH -> DateUtils.monthRange(today.year, today.monthValue)
            SearchRangeOption.LAST_MONTH -> {
                val last = today.minusMonths(1)
                DateUtils.monthRange(last.year, last.monthValue)
            }
            SearchRangeOption.LAST_7_DAYS -> {
                val start = today.minusDays(6)
                DateUtils.startOfDayMillis(start)..(DateUtils.startOfDayMillis(today.plusDays(1)) - 1)
            }
        }

    /** 金额下限：空/非法按 0。 */
    fun minCents(text: String): Long = MoneyFormatter.yuanToCents(text)?.coerceAtLeast(0) ?: 0L

    /** 金额上限：空/非法按无上限。 */
    fun maxCents(text: String): Long = MoneyFormatter.yuanToCents(text) ?: UNBOUNDED_MAX

    /** 区间合法：下限不得大于上限。 */
    fun amountRangeValid(
        minText: String,
        maxText: String,
    ): Boolean = minCents(minText) <= maxCents(maxText)
}
