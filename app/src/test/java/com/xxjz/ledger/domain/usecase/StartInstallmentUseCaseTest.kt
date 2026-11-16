package com.xxjz.ledger.domain.usecase

import com.xxjz.ledger.core.common.AppResult
import com.xxjz.ledger.core.common.DateUtils
import com.xxjz.ledger.domain.model.Account
import com.xxjz.ledger.domain.model.AccountType
import com.xxjz.ledger.domain.model.Txn
import com.xxjz.ledger.domain.model.TxnType
import com.xxjz.ledger.testutil.FakeAccountRepository
import com.xxjz.ledger.testutil.FakeInstallmentRepository
import com.xxjz.ledger.testutil.FakeTxnRepository
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

/** 分期作用于本笔交易（v1.9.5 第 9 条）。 */
@DisplayName("分期")
class StartInstallmentUseCaseTest {
    private val txnRepo = FakeTxnRepository()
    private val accountRepo = FakeAccountRepository()
    private val installmentRepo = FakeInstallmentRepository()
    private val start = StartInstallmentUseCase(txnRepo, accountRepo, installmentRepo)
    private val run = RunInstallmentUseCase(installmentRepo, txnRepo)
    private val day = DateUtils.toMillis(LocalDateTime.of(2026, 11, 16, 12, 0))

    private fun account(
        id: String,
        type: AccountType,
    ) {
        accountRepo.items[id] = Account(id = id, name = id, type = type, billDay = 15, repayDay = 20)
    }

    private fun expense(
        id: String = "t1",
        amount: Long = 300_000,
        accountId: String = "credit",
        type: TxnType = TxnType.EXPENSE,
    ) {
        txnRepo.items[id] =
            Txn(
                id = id,
                type = type,
                amount = amount,
                categoryId = if (type == TxnType.EXPENSE) "food" else null,
                accountId = accountId,
                dateTime = day,
                dateKey = DateUtils.dateKeyOf(day),
                note = "买电脑",
                createdAt = day,
                updatedAt = day,
            )
    }

    @Test
    @DisplayName("分 3 期后原流水金额改成本期金额")
    fun rewritesAmountToCurrentPeriod() =
        runTest {
            expense()
            account("credit", AccountType.CREDIT)

            val result = start("t1", periods = 3, now = day)
            assertTrue(result is AppResult.Success)

            val txn = txnRepo.items.getValue("t1")
            assertEquals(100_000L, txn.amount, "3000 元分 3 期，本期应是 1000")
        }

    @Test
    @DisplayName("除不尽的余数落在最后一期，总额不丢一分")
    fun remainderGoesToLastPeriod() =
        runTest {
            expense(amount = 100_000)
            account("credit", AccountType.CREDIT)
            start("t1", periods = 3, now = day)

            val plan = installmentRepo.items.values.single()
            val schedule = plan.schedule()
            assertEquals(100_000L, schedule.sumOf { it.totalCents })
            assertEquals(33_334L, schedule.last().totalCents)
        }

    @Test
    @DisplayName("已执行 1 期时，原流水落在第 2 期")
    fun paidPeriodsShiftsCurrent() =
        runTest {
            expense()
            account("credit", AccountType.CREDIT)
            start("t1", periods = 3, paidPeriods = 1, now = day)

            assertEquals(100_000L, txnRepo.items.getValue("t1").amount)
            assertEquals(1, installmentRepo.items.values.single().paidPeriods)
        }

    @Test
    @DisplayName("非信用账户不能分期")
    fun rejectsNonCreditAccount() =
        runTest {
            expense(accountId = "savings")
            account("savings", AccountType.SAVINGS)
            val result = start("t1", periods = 3, now = day)

            assertEquals("只有信用卡支出可以分期", (result as AppResult.Failure).error.message)
        }

    @Test
    @DisplayName("收入不能分期")
    fun rejectsIncome() =
        runTest {
            expense(type = TxnType.INCOME)
            account("credit", AccountType.CREDIT)
            val result = start("t1", periods = 3, now = day)

            assertEquals("只有支出可以分期", (result as AppResult.Failure).error.message)
        }

    @Test
    @DisplayName("期数越界被拒")
    fun rejectsBadPeriods() =
        runTest {
            expense()
            account("credit", AccountType.CREDIT)
            assertEquals(
                "期数要在 2 ~ 60 之间",
                (start("t1", periods = 1, now = day) as AppResult.Failure).error.message,
            )
            assertEquals(
                "期数要在 2 ~ 60 之间",
                (start("t1", periods = 61, now = day) as AppResult.Failure).error.message,
            )
        }

    @Test
    @DisplayName("已执行期数不能等于或超过总期数")
    fun rejectsPaidNotLessThanPeriods() =
        runTest {
            expense()
            account("credit", AccountType.CREDIT)
            assertEquals(
                "已执行期数必须小于总期数",
                (start("t1", periods = 3, paidPeriods = 3, now = day) as AppResult.Failure).error.message,
            )
        }

    @Test
    @DisplayName("跨月后自动生成下一期")
    fun generatesNextPeriodAfterAMonth() =
        runTest {
            expense()
            account("credit", AccountType.CREDIT)
            start("t1", periods = 3, now = day)

            val nextMonth = DateUtils.toMillis(LocalDateTime.of(2026, 12, 17, 0, 0))
            val created = run(nextMonth)

            assertEquals(1, created)
            val generated = txnRepo.items.values.filter { it.id != "t1" }.single()
            assertEquals(100_000L, generated.amount)
            assertEquals("买电脑（分期 2/3）", generated.note)
        }

    @Test
    @DisplayName("连着跑两次只生成一笔（幂等）")
    fun isIdempotent() =
        runTest {
            expense()
            account("credit", AccountType.CREDIT)
            start("t1", periods = 3, now = day)

            val nextMonth = DateUtils.toMillis(LocalDateTime.of(2026, 12, 17, 0, 0))
            run(nextMonth)
            val second = run(nextMonth)

            assertEquals(0, second)
            assertEquals(1, txnRepo.items.values.count { it.id != "t1" })
        }

    @Test
    @DisplayName("三个月没打开应用时逐期补齐，各落各月")
    fun catchesUpPeriodByPeriod() =
        runTest {
            expense()
            account("credit", AccountType.CREDIT)
            start("t1", periods = 4, now = day)

            val threeMonthsLater = DateUtils.toMillis(LocalDateTime.of(2027, 2, 20, 0, 0))
            val created = run(threeMonthsLater)

            assertEquals(3, created, "第 2、3、4 期都要补上")
            val months =
                txnRepo.items.values
                    .filter { it.id != "t1" }
                    .sortedBy { it.dateTime }
                    .map { DateUtils.toDateTime(it.dateTime).monthValue }
            assertEquals(listOf(12, 1, 2), months)
        }

    @Test
    @DisplayName("全部期数出完后不再生成")
    fun stopsWhenSettled() =
        runTest {
            expense()
            account("credit", AccountType.CREDIT)
            start("t1", periods = 2, now = day)

            val far = DateUtils.toMillis(LocalDateTime.of(2028, 1, 1, 0, 0))
            run(far)
            val again = run(far)

            assertEquals(0, again)
            assertEquals(1, txnRepo.items.values.count { it.id != "t1" })
        }

    @Test
    @DisplayName("原流水被删后停止排期，不凭空造账")
    fun stopsWhenSourceGone() =
        runTest {
            expense()
            account("credit", AccountType.CREDIT)
            start("t1", periods = 3, now = day)
            txnRepo.items.remove("t1")

            val nextMonth = DateUtils.toMillis(LocalDateTime.of(2026, 12, 17, 0, 0))
            assertEquals(0, run(nextMonth))
            assertNotNull(installmentRepo.items.values.single())
            assertEquals(0L, installmentRepo.items.values.single().nextRunAt)
        }

    @Test
    @DisplayName("分期计划挂在本笔流水上")
    fun planPointsAtTxn() =
        runTest {
            expense()
            account("credit", AccountType.CREDIT)
            start("t1", periods = 3, now = day)

            val plan = installmentRepo.items.values.single()
            assertEquals("t1", plan.txnId)
            assertEquals(300_000L, plan.principalCents)
            assertEquals(3, plan.periods)
            assertFalse(plan.settled)
        }
}
