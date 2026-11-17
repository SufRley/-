package com.xxjz.ledger.domain.usecase

import com.xxjz.ledger.core.common.DateUtils
import com.xxjz.ledger.domain.model.RecurringPeriod
import com.xxjz.ledger.domain.model.RecurringRule
import com.xxjz.ledger.domain.model.TxnType
import com.xxjz.ledger.testutil.FakeRecurringRepository
import com.xxjz.ledger.testutil.FakeTxnRepository
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * M7 执行引擎（AC-52、AC-54、AC-58）：
 * 幂等、启动补执行、暂停不补、次数与日期上限、立即执行。
 */
@DisplayName("周期记账执行引擎")
class RunRecurringUseCaseTest {
    private val recurringRepo = FakeRecurringRepository()
    private val txnRepo = FakeTxnRepository()
    private val useCase = RunRecurringUseCase(recurringRepo, txnRepo)

    private fun rule(
        id: String = "r1",
        next: LocalDate,
        period: RecurringPeriod = RecurringPeriod.MONTH,
        amount: Long = 3500,
        endAt: LocalDate? = null,
        maxTimes: Int? = null,
        runTimes: Int = 0,
        paused: Boolean = false,
    ): RecurringRule =
        RecurringRule(
            id = id,
            type = TxnType.EXPENSE,
            amount = amount,
            categoryId = "exp_0_0",
            accountId = "acc-1",
            note = "房租",
            period = period,
            nextRunAt = DateUtils.toMillis(next, LocalTime.of(9, 0)),
            endAt = endAt?.let { DateUtils.toMillis(it, LocalTime.of(23, 59)) },
            maxTimes = maxTimes,
            runTimes = runTimes,
            paused = paused,
        )

    private fun at(
        date: LocalDate,
        time: LocalTime = LocalTime.NOON,
    ): Long = DateUtils.toMillis(date, time)

    @Test
    @DisplayName("连续执行两次只生成一次账目（AC-58 幂等）")
    fun idempotentOnDoubleRun() =
        runTest {
            recurringRepo.put(rule(next = LocalDate.of(2026, 11, 1)))
            val now = at(LocalDate.of(2026, 11, 13))

            assertEquals(1, useCase(now))
            assertEquals(1, txnRepo.items.size)

            // 第二次（模拟 App 再次启动 / 后台任务再次触发）
            assertEquals(0, useCase(now))
            assertEquals(1, txnRepo.items.size)
        }

    @Test
    @DisplayName("跨月未打开：逐条补齐两个月（不重复、不漏）")
    fun catchesUpAllMissedRuns() =
        runTest {
            recurringRepo.put(rule(next = LocalDate.of(2026, 9, 5)))
            val created = useCase(at(LocalDate.of(2026, 11, 8)))

            assertEquals(3, created) // 9/5、10/5、11/5
            val dates = txnRepo.items.values.map { DateUtils.toDate(it.dateTime) }.sorted()
            assertEquals(
                listOf(LocalDate.of(2026, 9, 5), LocalDate.of(2026, 10, 5), LocalDate.of(2026, 11, 5)),
                dates,
            )
            // 时间保留原规则的 09:00
            assertTrue(txnRepo.items.values.all { DateUtils.toDateTime(it.dateTime).hour == 9 })
            // 游标已推到 12/5
            assertEquals(DateUtils.toMillis(LocalDate.of(2026, 12, 5), LocalTime.of(9, 0)), recurringRepo.items["r1"]?.nextRunAt)
            assertEquals(3, recurringRepo.items["r1"]?.runTimes)
        }

    @Test
    @DisplayName("暂停的规则不执行，恢复后也不补暂停期间的账")
    fun pausedNotRunNorBackfilled() =
        runTest {
            recurringRepo.put(rule(next = LocalDate.of(2026, 8, 1), paused = true))
            assertEquals(0, useCase(at(LocalDate.of(2026, 11, 13))))
            assertEquals(0, txnRepo.items.size)

            val resume = ToggleRecurringUseCase(recurringRepo)
            resume.resume("r1", at(LocalDate.of(2026, 11, 13)))
            val resumed = recurringRepo.items["r1"]!!
            assertFalse(resumed.paused)
            assertEquals(DateUtils.toMillis(LocalDate.of(2026, 12, 1), LocalTime.of(9, 0)), resumed.nextRunAt)

            assertEquals(0, useCase(at(LocalDate.of(2026, 11, 13))))
            assertEquals(0, txnRepo.items.size)
        }

    @Test
    @DisplayName("次数上限：执行满 3 次后自动结束，不再生成")
    fun stopsAtMaxTimes() =
        runTest {
            recurringRepo.put(rule(next = LocalDate.of(2026, 1, 1), maxTimes = 3))
            assertEquals(3, useCase(at(LocalDate.of(2026, 11, 13))))
            assertEquals(3, txnRepo.items.size)
            assertTrue(recurringRepo.items["r1"]!!.paused)

            assertEquals(0, useCase(at(LocalDate.of(2026, 12, 13))))
            assertEquals(3, txnRepo.items.size)
        }

    @Test
    @DisplayName("结束日期：越过 endAt 后不再生成")
    fun stopsAtEndDate() =
        runTest {
            recurringRepo.put(rule(next = LocalDate.of(2026, 1, 10), endAt = LocalDate.of(2026, 3, 31)))
            assertEquals(3, useCase(at(LocalDate.of(2026, 11, 13)))) // 1/10、2/10、3/10
            assertEquals(0, useCase(at(LocalDate.of(2026, 12, 13))))
        }

    @Test
    @DisplayName("立即执行：生成一笔并把游标推到下个周期")
    fun runNowCreatesAndAdvances() =
        runTest {
            recurringRepo.put(rule(next = LocalDate.of(2026, 12, 1)))
            val now = at(LocalDate.of(2026, 11, 13))
            val result = useCase.runNow("r1", now)

            assertTrue(result is com.xxjz.ledger.core.common.AppResult.Success)
            assertEquals(1, txnRepo.items.size)
            assertEquals(1, recurringRepo.items["r1"]?.runTimes)
            // 原游标 12/1 晚于 now，保持不变
            assertEquals(DateUtils.toMillis(LocalDate.of(2026, 12, 1), LocalTime.of(9, 0)), recurringRepo.items["r1"]?.nextRunAt)
        }

    @Test
    @DisplayName("立即执行：到期规则会顺手把游标推到 now 之后，避免重复补")
    fun runNowAdvancesOverdueCursor() =
        runTest {
            recurringRepo.put(rule(next = LocalDate.of(2026, 10, 1)))
            val now = at(LocalDate.of(2026, 11, 13))
            useCase.runNow("r1", now)
            assertEquals(DateUtils.toMillis(LocalDate.of(2026, 12, 1), LocalTime.of(9, 0)), recurringRepo.items["r1"]?.nextRunAt)
            // 之后正常补执行不会再补 10/1、11/1
            assertEquals(0, useCase(now))
        }

    @Test
    @DisplayName("周规则补执行：8 周未打开生成 8 笔")
    fun weeklyCatchUp() =
        runTest {
            recurringRepo.put(
                // 2026-09-19 是周六
                rule(
                    next = LocalDate.of(2026, 9, 19),
                    period = RecurringPeriod.WEEK,
                ),
            )
            // 9/19 起到 11/7 共 8 周
            assertEquals(8, useCase(at(LocalDate.of(2026, 11, 13))))
        }

    @Test
    @DisplayName("多条规则一次补执行，互不影响")
    fun multipleRules() =
        runTest {
            recurringRepo.put(rule(id = "rent", next = LocalDate.of(2026, 10, 1), amount = 200_000))
            recurringRepo.put(rule(id = "netflix", next = LocalDate.of(2026, 11, 1), amount = 2500))
            // 房租 10/1、11/1 两笔 + 会员 11/1 一笔
            assertEquals(3, useCase(at(LocalDate.of(2026, 11, 13))))
            assertEquals(3, txnRepo.items.size)
            assertTrue(txnRepo.items.values.any { it.amount == 200_000L })
            assertTrue(txnRepo.items.values.any { it.amount == 2500L })
        }
}
