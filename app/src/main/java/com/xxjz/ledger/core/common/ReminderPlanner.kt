package com.xxjz.ledger.core.common

/** 每日提醒的计划（AC-85）。 */
data class ReminderPlan(
    /** 是否要排下一次提醒。 */
    val scheduled: Boolean,
    /** 下一次触发的毫秒时间戳；[scheduled] 为 false 时无意义。 */
    val nextAt: Long,
) {
    companion object {
        val NONE = ReminderPlan(scheduled = false, nextAt = 0)
    }
}

/**
 * 每日提醒时间的计算。
 *
 * 规则（与设置页保持一致）：
 * - 开关关掉 → 不排；
 * - 开关打开但今天的时间点还没到 → 今天触发；
 * - 已经过了今天的时间点 → 明天同一时刻触发。
 *
 * 全部用 `LocalDateTime` + [java.time.ZoneId] 算，不做「+86400000」这类加法——
 * 夏令时与跨月时那样算会漂移一小时或漂一天。
 */
object ReminderPlanner {
    /** 默认提醒时间：晚上 21:00。 */
    const val DEFAULT_MINUTE_OF_DAY = 21 * 60

    fun next(
        now: Long,
        enabled: Boolean,
        minuteOfDay: Int,
        zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
    ): ReminderPlan {
        if (!enabled) return ReminderPlan.NONE
        val safeMinute = minuteOfDay.coerceIn(0, 24 * 60 - 1)
        val hour = safeMinute / 60
        val minute = safeMinute % 60
        val nowDateTime = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDateTime()
        var target = nowDateTime.toLocalDate().atTime(hour, minute)
        if (!target.isAfter(nowDateTime)) {
            target = target.plusDays(1)
        }
        return ReminderPlan(scheduled = true, nextAt = target.atZone(zone).toInstant().toEpochMilli())
    }

    /** 把「几分钟」显示成 `21:00`，设置页与通知文案共用。 */
    fun label(minuteOfDay: Int): String {
        val safe = minuteOfDay.coerceIn(0, 24 * 60 - 1)
        return "%02d:%02d".format(safe / 60, safe % 60)
    }

    /** 校验设置页输入的时间是否合法（0:00 ~ 23:59）。 */
    fun isValid(minuteOfDay: Int): Boolean = minuteOfDay in 0..(24 * 60 - 1)
}
