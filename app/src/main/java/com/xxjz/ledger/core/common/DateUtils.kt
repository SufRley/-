package com.xxjz.ledger.core.common

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 日期工具：日期以 epochMillis 存储，冗余 dateKey=yyyyMMdd 便于按天分组。 */
object DateUtils {
    val zone: ZoneId get() = ZoneId.systemDefault()
    private val YMD: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")

    fun dateKeyOf(date: LocalDate): Int = date.year * 10_000 + date.monthValue * 100 + date.dayOfMonth

    fun dateKeyOf(millis: Long): Int = dateKeyOf(toDate(millis))

    fun toDate(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

    fun toDateTime(millis: Long): LocalDateTime = Instant.ofEpochMilli(millis).atZone(zone).toLocalDateTime()

    fun toMillis(dateTime: LocalDateTime): Long = dateTime.atZone(zone).toInstant().toEpochMilli()

    fun toMillis(
        date: LocalDate,
        time: java.time.LocalTime = java.time.LocalTime.now(),
    ): Long = LocalDateTime.of(date, time).atZone(zone).toInstant().toEpochMilli()

    fun dateKeyToDate(key: Int): LocalDate = LocalDate.of(key / 10_000, key / 100 % 100, key % 100)

    fun today(): LocalDate = LocalDate.now(zone)

    fun startOfDayMillis(date: LocalDate): Long = toMillis(date, java.time.LocalTime.MIDNIGHT)

    fun monthRange(
        year: Int,
        month: Int,
    ): LongRange {
        val ym = YearMonth.of(year, month)
        val start = startOfDayMillis(ym.atDay(1))
        val end = startOfDayMillis(ym.plusMonths(1).atDay(1)) - 1
        return start..end
    }

    fun monthKeyOf(date: LocalDate): Int = date.year * 100 + date.monthValue

    /** 所在周（周一为首）的区间毫秒。 */
    fun weekRange(date: LocalDate): LongRange {
        val monday = date.minusDays(((date.dayOfWeek.value + 6) % 7).toLong())
        return startOfDayMillis(monday)..(startOfDayMillis(monday.plusDays(7)) - 1)
    }

    /** 整年的区间毫秒。 */
    fun yearRange(year: Int): LongRange = startOfDayMillis(LocalDate.of(year, 1, 1))..(startOfDayMillis(LocalDate.of(year + 1, 1, 1)) - 1)

    fun yearOf(key: Int): Int = key / 100

    fun monthOf(key: Int): Int = key % 100

    /** 当月剩余天数（含今天）。 */
    fun remainingDaysInMonth(today: LocalDate = today()): Int {
        val last = today.lengthOfMonth()
        return last - today.dayOfMonth + 1
    }

    /** 已过天数（含今天）。 */
    fun elapsedDaysInMonth(today: LocalDate = today()): Int = today.dayOfMonth

    fun daysInMonth(
        year: Int,
        month: Int,
    ): Int = YearMonth.of(year, month).lengthOfMonth()

    /** 相对日期文案：今天 / 昨天 / 前天 / M月d日。 */
    fun friendlyDate(
        date: LocalDate,
        today: LocalDate = today(),
    ): String =
        when (date) {
            today -> "今天"
            today.minusDays(1) -> "昨天"
            today.minusDays(2) -> "前天"
            else -> "${date.monthValue}月${date.dayOfMonth}日"
        }

    fun ymdText(date: LocalDate): String = "%d-%02d-%02d".format(date.year, date.monthValue, date.dayOfMonth)

    /** 周期推算：按日/周/月/年叠加，月末不足时按 LocalDate 规则回退（1/31 +1 月 = 2/28）。 */
    fun plusPeriod(
        base: LocalDate,
        period: String,
        count: Int = 1,
    ): LocalDate =
        when (period) {
            "DAY" -> base.plusDays(count.toLong())
            "WEEK" -> base.plusWeeks(count.toLong())
            "MONTH" -> base.plusMonths(count.toLong())
            "YEAR" -> base.plusYears(count.toLong())
            else -> base
        }

    val displayFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm")

    /** 毫秒时间戳 → 「2026年11月13日 21:45」。 */
    fun formatDateTime(millis: Long): String = java.time.Instant.ofEpochMilli(millis).atZone(zone).format(displayFormatter)

    /** 日期 → 「2026-11-13」。 */
    fun formatDate(date: LocalDate): String = ymdText(date)
}
