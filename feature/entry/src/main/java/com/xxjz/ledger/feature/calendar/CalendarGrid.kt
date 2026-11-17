package com.xxjz.ledger.feature.calendar

import java.time.LocalDate

/** 日历格：本月外的补位为 null。 */
data class CalendarCell(
    val date: LocalDate?,
)

/**
 * 月历网格（纯函数）：周一为每周第一天，前后补空位凑满整周。
 * 单独抽出来是为了单测跨月、闰年、月末对齐这些容易写错的地方。
 */
object CalendarGrid {
    fun build(
        year: Int,
        month: Int,
    ): List<CalendarCell> {
        val first = LocalDate.of(year, month, 1)
        val days = first.lengthOfMonth()
        val leading = (first.dayOfWeek.value + 6) % 7
        val cells = ArrayList<CalendarCell>(42)
        repeat(leading) { cells += CalendarCell(null) }
        for (day in 1..days) cells += CalendarCell(LocalDate.of(year, month, day))
        while (cells.size % 7 != 0) cells += CalendarCell(null)
        return cells
    }

    val weekLabels: List<String> = listOf("一", "二", "三", "四", "五", "六", "日")
}
