package com.xxjz.ledger.core.common

import com.xxjz.ledger.domain.model.ReminderMessage

import com.xxjz.ledger.domain.model.Txn
import com.xxjz.ledger.domain.model.TxnType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** M9-B 每日提醒（AC-85）：时间计算与「今天记过账就别烦我」的判断。 */
@DisplayName("每日提醒")
class ReminderPlannerTest {
    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    private fun at(
        date: LocalDate,
        time: String,
    ): Long = DateUtils.toMillis(date.atTime(LocalTime.parse(time)))

    private fun txn(
        dateKey: Int,
        deleted: Boolean = false,
    ) = Txn(
        id = "t$dateKey",
        type = TxnType.EXPENSE,
        amount = 100,
        categoryId = null,
        accountId = null,
        dateTime = 0,
        dateKey = dateKey,
        deletedAt = if (deleted) 1L else null,
        createdAt = 0,
        updatedAt = 0,
    )

    @Nested
    @DisplayName("下一次提醒时间")
    inner class Plan {
        @Test
        @DisplayName("今天的时间点还没到 → 就排今天")
        fun laterToday() {
            val now = at(LocalDate.of(2026, 11, 14), "09:00")
            val plan = ReminderPlanner.next(now, enabled = true, minuteOfDay = 21 * 60, zone = zone)
            assertTrue(plan.scheduled)
            assertEquals(at(LocalDate.of(2026, 11, 14), "21:00"), plan.nextAt)
        }

        @Test
        @DisplayName("今天的时间点已经过了 → 排到明天同一时刻")
        fun tomorrow() {
            val now = at(LocalDate.of(2026, 11, 14), "22:30")
            val plan = ReminderPlanner.next(now, enabled = true, minuteOfDay = 21 * 60, zone = zone)
            assertEquals(at(LocalDate.of(2026, 11, 15), "21:00"), plan.nextAt)
        }

        @Test
        @DisplayName("正好卡在提醒时刻上 → 排明天，不会立刻再响一次")
        fun exactlyNow() {
            val now = at(LocalDate.of(2026, 11, 14), "21:00")
            assertEquals(
                at(LocalDate.of(2026, 11, 15), "21:00"),
                ReminderPlanner.next(now, enabled = true, minuteOfDay = 21 * 60, zone = zone).nextAt,
            )
        }

        @Test
        @DisplayName("跨月跨年都算对：12 月 31 日过点后是次年 1 月 1 日")
        fun crossYear() {
            val now = at(LocalDate.of(2026, 12, 31), "23:50")
            assertEquals(
                at(LocalDate.of(2027, 1, 1), "08:00"),
                ReminderPlanner.next(now, enabled = true, minuteOfDay = 8 * 60, zone = zone).nextAt,
            )
        }

        @Test
        @DisplayName("开关关掉就不排任务")
        fun disabled() {
            val plan = ReminderPlanner.next(System.currentTimeMillis(), enabled = false, minuteOfDay = 21 * 60, zone = zone)
            assertFalse(plan.scheduled)
            assertEquals(ReminderPlan.NONE, plan)
        }

        @Test
        @DisplayName("时间越界会被夹到合法区间，不会抛异常")
        fun clamp() {
            val now = at(LocalDate.of(2026, 11, 14), "09:00")
            // -30 → 夹到 00:00，今天已过 → 排明天 00:00
            assertEquals(
                at(LocalDate.of(2026, 11, 15), "00:00"),
                ReminderPlanner.next(now, enabled = true, minuteOfDay = -30, zone = zone).nextAt,
            )
            // 10000 → 夹到 23:59，今天还没到 → 排今天 23:59
            assertEquals(
                at(LocalDate.of(2026, 11, 14), "23:59"),
                ReminderPlanner.next(now, enabled = true, minuteOfDay = 10_000, zone = zone).nextAt,
            )
        }

        @Test
        @DisplayName("文案格式 21:00 / 08:05，非法值判为非法")
        fun label() {
            assertEquals("21:00", ReminderPlanner.label(21 * 60))
            assertEquals("08:05", ReminderPlanner.label(8 * 60 + 5))
            assertEquals("00:00", ReminderPlanner.label(0))
            assertTrue(ReminderPlanner.isValid(0))
            assertTrue(ReminderPlanner.isValid(23 * 60 + 59))
            assertFalse(ReminderPlanner.isValid(-1))
            assertFalse(ReminderPlanner.isValid(24 * 60))
        }
    }

    @Nested
    @DisplayName("该不该发")
    inner class Notify {
        private val todayKey = 20261114

        @Test
        @DisplayName("今天还没记账 → 发")
        fun noTxnToday() {
            assertTrue(ReminderMessage.shouldNotify(enabled = true, txnsToday = emptyList(), dateKey = todayKey))
        }

        @Test
        @DisplayName("今天已经记过 → 不发")
        fun recorded() {
            assertFalse(ReminderMessage.shouldNotify(true, listOf(txn(todayKey)), todayKey))
        }

        @Test
        @DisplayName("今天那条被软删了 → 视为没记，照发")
        fun deleted() {
            assertTrue(ReminderMessage.shouldNotify(true, listOf(txn(todayKey, deleted = true)), todayKey))
        }

        @Test
        @DisplayName("开关关了 → 一律不发")
        fun off() {
            assertFalse(ReminderMessage.shouldNotify(false, emptyList(), todayKey))
        }

        @Test
        @DisplayName("别把「昨天记过」当成今天记过")
        fun yesterday() {
            assertTrue(ReminderMessage.hasTxnToday(listOf(txn(20261113)), todayKey).not())
        }
    }
}
