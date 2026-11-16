package com.xxjz.ledger.domain.model

import com.xxjz.ledger.core.common.DateUtils
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

/** M7 周期推算：跨月/跨年/2 月 29 日、补执行次数、结束条件、恢复不补（AC-52、AC-55 ~ AC-58）。 */
@DisplayName("周期规则推算")
class RecurringCalcTest {
    private fun rule(
        next: LocalDate,
        period: RecurringPeriod = RecurringPeriod.MONTH,
        interval: Int = 1,
        endAt: LocalDate? = null,
        maxTimes: Int? = null,
        runTimes: Int = 0,
        paused: Boolean = false,
    ): RecurringRule =
        RecurringRule(
            id = "r1",
            type = TxnType.EXPENSE,
            amount = 1000,
            categoryId = "exp_0_0",
            accountId = "acc",
            period = period,
            interval = interval,
            nextRunAt = DateUtils.toMillis(next, LocalTime.of(9, 0)),
            endAt = endAt?.let { DateUtils.toMillis(it, LocalTime.of(23, 59)) },
            maxTimes = maxTimes,
            runTimes = runTimes,
            paused = paused,
        )

    private fun millis(date: LocalDate): Long = DateUtils.toMillis(date, LocalTime.of(9, 0))

    @Test
    @DisplayName("1 月 31 日按月推进落到 2 月 28 日，而不是 3 月 3 日")
    fun monthEndBacksOff() {
        val r = rule(LocalDate.of(2026, 1, 31))
        assertEquals(millis(LocalDate.of(2026, 2, 28)), RecurringCalc.plusOnce(r, r.nextRunAt))
    }

    @Test
    @DisplayName("2 月 29 日的年度规则次年落到 2 月 28 日")
    fun leapDayYearly() {
        val r = rule(LocalDate.of(2024, 2, 29), period = RecurringPeriod.YEAR)
        val next = RecurringCalc.plusOnce(r, r.nextRunAt)
        assertEquals(millis(LocalDate.of(2025, 2, 28)), next)
    }

    @Test
    @DisplayName("跨年周规则按 7 天推进，保留时分")
    fun weeklyAcrossYear() {
        val r = rule(LocalDate.of(2026, 12, 28), period = RecurringPeriod.WEEK)
        val next = RecurringCalc.plusOnce(r, r.nextRunAt)
        assertEquals(millis(LocalDate.of(2027, 1, 4)), next)
        assertEquals(9, DateUtils.toDateTime(next).hour)
    }

    @Test
    @DisplayName("间隔 2 个月按 2 个月推进")
    fun intervalRespected() {
        val r = rule(LocalDate.of(2026, 1, 15), interval = 2)
        assertEquals(millis(LocalDate.of(2026, 3, 15)), RecurringCalc.plusOnce(r, r.nextRunAt))
    }

    @Test
    @DisplayName("补执行：3 次应执行时刻全部补齐，游标推到 now 之后")
    fun planCatchesUp() {
        val r = rule(LocalDate.of(2026, 1, 15))
        val now = DateUtils.toMillis(LocalDate.of(2026, 4, 1), LocalTime.NOON)
        val plan = RecurringCalc.plan(r, now)
        assertEquals(3, plan.runAts.size) // 1/15、2/15、3/15
        assertEquals(millis(LocalDate.of(2026, 4, 15)), plan.nextRunAt)
        assertEquals(3, plan.runTimes)
        assertFalse(plan.finished)
    }

    @Test
    @DisplayName("幂等：把计划写回后再算一次是空计划")
    fun planIsIdempotent() {
        val r = rule(LocalDate.of(2026, 1, 15))
        val now = DateUtils.toMillis(LocalDate.of(2026, 4, 1), LocalTime.NOON)
        val first = RecurringCalc.plan(r, now)
        val applied = r.copy(nextRunAt = first.nextRunAt, runTimes = first.runTimes)
        val second = RecurringCalc.plan(applied, now)
        assertTrue(second.runAts.isEmpty())
        assertEquals(first.nextRunAt, second.nextRunAt)
    }

    @Test
    @DisplayName("次数上限：最多补到设定次数，之后标记结束")
    fun maxTimesStops() {
        val r = rule(LocalDate.of(2026, 1, 15), maxTimes = 2)
        val now = DateUtils.toMillis(LocalDate.of(2026, 6, 1), LocalTime.NOON)
        val plan = RecurringCalc.plan(r, now)
        assertEquals(2, plan.runAts.size)
        assertTrue(plan.finished)
        assertEquals(RecurringStatus.FINISHED, RecurringCalc.statusOf(r.copy(runTimes = 2)))
    }

    @Test
    @DisplayName("结束日期：越过 endAt 的时刻不生成")
    fun endDateStops() {
        val r = rule(LocalDate.of(2026, 1, 15), endAt = LocalDate.of(2026, 2, 20))
        val now = DateUtils.toMillis(LocalDate.of(2026, 6, 1), LocalTime.NOON)
        val plan = RecurringCalc.plan(r, now)
        assertEquals(2, plan.runAts.size) // 1/15、2/15；3/15 已越过 endAt
        assertTrue(plan.finished)
    }

    @Test
    @DisplayName("恢复暂停：游标推到 now 之后，暂停期间不补")
    fun resumeSkipsPausedWindow() {
        val r = rule(LocalDate.of(2026, 1, 10), paused = true)
        val now = DateUtils.toMillis(LocalDate.of(2026, 3, 20), LocalTime.NOON)
        val next = RecurringCalc.nextAfterResume(r, now)
        assertEquals(millis(LocalDate.of(2026, 4, 10)), next)
    }

    @Test
    @DisplayName("状态与文案：进行中 / 已暂停 / 已结束")
    fun statusAndLabels() {
        val active = rule(LocalDate.of(2026, 1, 10))
        assertEquals(RecurringStatus.ACTIVE, RecurringCalc.statusOf(active))
        assertEquals("进行中", RecurringCalc.statusLabel(active))
        assertEquals("每月", RecurringCalc.periodLabel(active))
        assertEquals("每 2 个月", RecurringCalc.periodLabel(active.copy(interval = 2)))

        val paused = active.copy(paused = true)
        assertEquals(RecurringStatus.PAUSED, RecurringCalc.statusOf(paused))
        assertEquals("已暂停", RecurringCalc.statusLabel(paused))

        val finished = active.copy(maxTimes = 3, runTimes = 3)
        assertEquals(RecurringStatus.FINISHED, RecurringCalc.statusOf(finished))
        assertEquals("已结束", RecurringCalc.statusLabel(finished))
    }

    @Test
    @DisplayName("nextRunLabel：今天 / 明天 / 具体日期")
    fun nextRunLabel() {
        val today = LocalDate.of(2026, 11, 13)
        val r = rule(today)
        assertEquals("今天", RecurringCalc.nextRunLabel(r, today))
        assertEquals("明天", RecurringCalc.nextRunLabel(r.copy(nextRunAt = millis(today.plusDays(1))), today))
        assertEquals("11月20日", RecurringCalc.nextRunLabel(r.copy(nextRunAt = millis(today.plusDays(7))), today))
    }

    @Test
    @DisplayName("needsCatchUp：只有进行中且已到期的规则需要补执行")
    fun needsCatchUp() {
        val today = LocalDate.of(2026, 11, 13)
        val now = DateUtils.toMillis(today, LocalTime.NOON)
        val due = rule(today.minusDays(3))
        assertTrue(RecurringCalc.needsCatchUp(due, now))
        assertFalse(RecurringCalc.needsCatchUp(due.copy(paused = true), now))
        assertFalse(RecurringCalc.needsCatchUp(due.copy(maxTimes = 1, runTimes = 1), now))
        assertFalse(RecurringCalc.needsCatchUp(rule(today.plusDays(1)), now))
    }
}
