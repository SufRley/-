package com.xxjz.ledger.feature.recurring

import com.xxjz.ledger.core.common.DateUtils
import com.xxjz.ledger.domain.model.RecurringPeriod
import com.xxjz.ledger.domain.model.RecurringRule
import java.time.LocalDateTime

/** 周期规则的展示状态。 */
enum class RecurringStatus { ACTIVE, PAUSED, FINISHED }

/** 一次补执行的计算结果：要生成的执行时刻 + 执行后的规则游标。 */
data class RunPlan(
    /** 需要生成的账目时刻，升序。 */
    val runAts: List<Long>,
    /** 执行完后写回的 `nextRunAt`。 */
    val nextRunAt: Long,
    /** 执行完后写回的已执行次数。 */
    val runTimes: Int,
    /** 结束后置 true：次数用完或越过结束日，之后不再触发。 */
    val finished: Boolean,
)

/**
 * 周期规则的纯计算逻辑（AC-52、AC-57、AC-58）。
 *
 * 约定：
 * - `nextRunAt` 是「下一次该执行的时刻」，执行一次后立刻前移，所以重复调用天然幂等；
 * - 月末不足按 `LocalDate` 规则回退（1/31 + 1 月 = 2/28，2/29 + 1 年 = 2/28），不特判；
 * - 暂停期间不补执行，恢复时把游标一次性推到 `now` 之后。
 */
object RecurringCalc {
    /** 单条规则单次补执行的上限，防止长期未打开 App 时一次生成上万条。 */
    const val MAX_CATCH_UP = 366

    /** 从 [from] 起再走一个周期，保留原时刻（时/分）。 */
    fun plusOnce(
        rule: RecurringRule,
        from: Long,
    ): Long {
        val dt: LocalDateTime = DateUtils.toDateTime(from)
        val next = DateUtils.plusPeriod(dt.toLocalDate(), rule.period.name, rule.interval.coerceAtLeast(1))
        return DateUtils.toMillis(LocalDateTime.of(next, dt.toLocalTime()))
    }

    /** 次数用完或越过结束日即视为结束。 */
    fun isFinished(rule: RecurringRule): Boolean =
        (rule.maxTimes != null && rule.runTimes >= rule.maxTimes) ||
            (rule.endAt != null && rule.nextRunAt > rule.endAt)

    fun statusOf(rule: RecurringRule): RecurringStatus =
        when {
            isFinished(rule) -> RecurringStatus.FINISHED
            rule.paused -> RecurringStatus.PAUSED
            else -> RecurringStatus.ACTIVE
        }

    /**
     * 计算「截止 [now] 应该补齐的执行时刻」。
     *
     * 逐条前移游标，既补历史漏执行，又不会重复：只要调用方把 [RunPlan.nextRunAt] 写回，
     * 下次调用就是空计划（AC-58 幂等的实现基础）。
     */
    fun plan(
        rule: RecurringRule,
        now: Long,
        maxRuns: Int = MAX_CATCH_UP,
    ): RunPlan {
        var cursor = rule.nextRunAt
        var times = rule.runTimes
        val runs = mutableListOf<Long>()
        while (runs.size < maxRuns) {
            if (rule.endAt != null && cursor > rule.endAt) break
            if (rule.maxTimes != null && times >= rule.maxTimes) break
            if (cursor > now) break
            runs += cursor
            times += 1
            cursor = plusOnce(rule, cursor)
        }
        val finished =
            (rule.maxTimes != null && times >= rule.maxTimes) ||
                (rule.endAt != null && cursor > rule.endAt)
        return RunPlan(runs, cursor, times, finished)
    }

    /** 恢复暂停：游标推到 [now] 之后，暂停期间的时刻全部丢弃（AC-54）。 */
    fun nextAfterResume(
        rule: RecurringRule,
        now: Long,
    ): Long {
        var cursor = rule.nextRunAt
        var guard = 0
        while (cursor <= now && guard < MAX_CATCH_UP) {
            cursor = plusOnce(rule, cursor)
            guard++
        }
        return cursor
    }

    /** 周期文案：每 1 个月 → 每月。 */
    fun periodLabel(rule: RecurringRule): String {
        val unit =
            when (rule.period) {
                RecurringPeriod.DAY -> "天"
                RecurringPeriod.WEEK -> "周"
                RecurringPeriod.MONTH -> "月"
                RecurringPeriod.YEAR -> "年"
            }
        if (rule.interval <= 1) return "每$unit"
        return if (rule.period == RecurringPeriod.MONTH) "每 ${rule.interval} 个月" else "每 ${rule.interval} $unit"
    }

    /** 列表右侧的状态文案。 */
    fun statusLabel(rule: RecurringRule): String =
        when (statusOf(rule)) {
            RecurringStatus.ACTIVE -> "进行中"
            RecurringStatus.PAUSED -> "已暂停"
            RecurringStatus.FINISHED -> "已结束"
        }

    /** 「下次执行」文案：今天 / 明天 / M月d日。 */
    fun nextRunLabel(
        rule: RecurringRule,
        today: java.time.LocalDate = DateUtils.today(),
    ): String {
        if (isFinished(rule)) return "已结束"
        val date = DateUtils.toDate(rule.nextRunAt)
        return when (date) {
            today -> "今天"
            today.plusDays(1) -> "明天"
            else -> "${date.monthValue}月${date.dayOfMonth}日"
        }
    }

    /** 已经开始且已过期的规则：用于首页「有 N 笔周期账待补」提示。 */
    fun needsCatchUp(
        rule: RecurringRule,
        now: Long,
    ): Boolean = !rule.paused && !isFinished(rule) && rule.nextRunAt <= now
}
