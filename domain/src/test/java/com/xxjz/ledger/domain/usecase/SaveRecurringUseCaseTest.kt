package com.xxjz.ledger.domain.usecase

import com.xxjz.ledger.core.common.AppError
import com.xxjz.ledger.core.common.AppResult
import com.xxjz.ledger.core.common.DateUtils
import com.xxjz.ledger.domain.model.RecurringPeriod
import com.xxjz.ledger.domain.model.RecurringRule
import com.xxjz.ledger.domain.model.TxnType
import com.xxjz.ledger.testutil.FakeRecurringRepository
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

/** M7 周期规则编辑与暂停恢复（AC-54 ~ AC-56）。 */
@DisplayName("周期规则编辑")
class SaveRecurringUseCaseTest {
    private val repo = FakeRecurringRepository()
    private val save = SaveRecurringUseCase(repo)
    private val toggle = ToggleRecurringUseCase(repo)

    private val start: Long = DateUtils.toMillis(LocalDate.of(2026, 12, 1), LocalTime.of(8, 30))

    @Suppress("LongParameterList") // 测试造数工厂，字段一一对应 RecurringDraft
    private fun draft(
        id: String? = null,
        type: TxnType = TxnType.EXPENSE,
        amount: Long = 200_000,
        categoryId: String? = "exp_3_0",
        accountId: String? = "acc-1",
        toAccountId: String? = null,
        period: RecurringPeriod = RecurringPeriod.MONTH,
        interval: Int = 1,
        endMode: RecurringEndMode = RecurringEndMode.FOREVER,
        maxTimes: Int? = null,
        endAt: Long? = null,
        startAt: Long = start,
    ) = RecurringDraft(
        id = id,
        type = type,
        amountCents = amount,
        categoryId = categoryId,
        accountId = accountId,
        toAccountId = toAccountId,
        note = " 房租 ",
        period = period,
        interval = interval,
        startAt = startAt,
        endMode = endMode,
        maxTimes = maxTimes,
        endAt = endAt,
    )

    @Test
    @DisplayName("新建：落库成功，首执行时间等于开始时间，备注去空格")
    fun create() =
        runTest {
            val result = save(draft())
            assertTrue(result is AppResult.Success)
            val rule = repo.items.values.single()
            assertEquals(start, rule.nextRunAt)
            assertEquals("房租", rule.note)
            assertEquals(0, rule.runTimes)
            assertNull(rule.endAt)
        }

    @Test
    @DisplayName("金额必须为正 / 分类必填 / 账户必填")
    fun validations() =
        runTest {
            assertEquals(AppError.AmountNotPositive, (save(draft(amount = 0)) as AppResult.Failure).error)
            assertEquals(AppError.CategoryRequired, (save(draft(categoryId = null)) as AppResult.Failure).error)
            assertEquals(AppError.AccountRequired, (save(draft(accountId = null)) as AppResult.Failure).error)
        }

    @Test
    @DisplayName("转账必须选不同的转入账户")
    fun transferSameAccount() =
        runTest {
            val bad = save(draft(type = TxnType.TRANSFER, categoryId = null, toAccountId = "acc-1"))
            assertEquals(AppError.SameAccount, (bad as AppResult.Failure).error)
            val ok = save(draft(type = TxnType.TRANSFER, categoryId = null, toAccountId = "acc-2"))
            assertTrue(ok is AppResult.Success)
            assertNull(repo.items.values.single().categoryId)
        }

    @Test
    @DisplayName("次数结束条件必须 > 0；结束日期不能早于开始日期")
    fun endModeValidation() =
        runTest {
            val count = save(draft(endMode = RecurringEndMode.COUNT, maxTimes = 0))
            assertEquals("执行次数必须大于 0", (count as AppResult.Failure).error.message)

            val until =
                save(
                    draft(
                        endMode = RecurringEndMode.UNTIL,
                        endAt = DateUtils.toMillis(LocalDate.of(2026, 11, 1)),
                    ),
                )
            assertEquals("结束日期不能早于开始日期", (until as AppResult.Failure).error.message)
        }

    @Test
    @DisplayName("编辑金额不动游标；改开始日则重置游标与已执行次数")
    fun editKeepsOrResetsCursor() =
        runTest {
            repo.put(
                RecurringRule(
                    id = "r1",
                    type = TxnType.EXPENSE,
                    amount = 100_000,
                    categoryId = "exp_3_0",
                    accountId = "acc-1",
                    period = RecurringPeriod.MONTH,
                    nextRunAt = start,
                    runTimes = 5,
                ),
            )

            // 同一天改金额：游标与次数保留
            save(draft(id = "r1", amount = 120_000))
            assertEquals(5, repo.items["r1"]?.runTimes)
            assertEquals(start, repo.items["r1"]?.nextRunAt)
            assertEquals(120_000, repo.items["r1"]?.amount)

            // 改开始日：重新排队
            val later = DateUtils.toMillis(LocalDate.of(2027, 1, 1), LocalTime.of(8, 30))
            save(draft(id = "r1", amount = 120_000, startAt = later))
            assertEquals(0, repo.items["r1"]?.runTimes)
            assertEquals(later, repo.items["r1"]?.nextRunAt)
        }

    @Test
    @DisplayName("暂停保留游标，恢复把游标推到当前时刻之后")
    fun pauseAndResume() =
        runTest {
            repo.put(
                RecurringRule(
                    id = "r1",
                    type = TxnType.EXPENSE,
                    amount = 1000,
                    categoryId = "exp_0_0",
                    accountId = "acc-1",
                    period = RecurringPeriod.MONTH,
                    nextRunAt = DateUtils.toMillis(LocalDate.of(2026, 10, 5), LocalTime.of(9, 0)),
                ),
            )
            val now = DateUtils.toMillis(LocalDate.of(2026, 11, 13), LocalTime.NOON)
            assertTrue(toggle.pause("r1"))
            assertTrue(repo.items["r1"]!!.paused)
            assertNotNull(repo.items["r1"]!!.nextRunAt)

            assertTrue(toggle.resume("r1", now))
            assertEquals(DateUtils.toMillis(LocalDate.of(2026, 12, 5), LocalTime.of(9, 0)), repo.items["r1"]?.nextRunAt)
        }
}
