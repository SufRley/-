package com.xxjz.ledger.domain.usecase

import com.xxjz.ledger.core.common.AppError
import com.xxjz.ledger.core.common.AppResult
import com.xxjz.ledger.core.common.DateUtils
import com.xxjz.ledger.domain.model.Debt
import com.xxjz.ledger.domain.model.DebtDirection
import com.xxjz.ledger.domain.model.TxnType
import com.xxjz.ledger.testutil.FakeDebtRepository
import com.xxjz.ledger.testutil.FakeTxnRepository
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

/** M7 借贷台账：新建校验、部分还款、自动结清、逾期、坏账（AC-63 ~ AC-67）。 */
@DisplayName("借贷用例")
class DebtUseCasesTest {
    private val txnRepo = FakeTxnRepository()
    private val debtRepo = FakeDebtRepository(txnRepo)
    private val save = SaveDebtUseCase(debtRepo)
    private val repay = RepayDebtUseCase(debtRepo, txnRepo)
    private val markBad = MarkDebtBadUseCase(debtRepo)

    private val startAt = DateUtils.toMillis(LocalDate.of(2026, 10, 1), LocalTime.NOON)
    private val dueAt = DateUtils.toMillis(LocalDate.of(2026, 12, 1), LocalTime.NOON)
    private val now = DateUtils.toMillis(LocalDate.of(2026, 11, 13), LocalTime.NOON)

    private fun draft(
        direction: DebtDirection = DebtDirection.LEND,
        counterparty: String = "小王",
        principal: Long = 50_000,
        due: Long? = dueAt,
    ) = DebtDraft(
        direction = direction,
        counterparty = counterparty,
        principalCents = principal,
        accountId = "acc-1",
        startAt = startAt,
        dueAt = due,
        note = " 借款 ",
    )

    @Test
    @DisplayName("新建借贷：对方必填、金额为正、还款日不能早于借款日")
    fun saveValidation() =
        runTest {
            assertEquals(AppError.DebtCounterpartyRequired, (save(draft(counterparty = " ")) as AppResult.Failure).error)
            assertEquals(AppError.AmountNotPositive, (save(draft(principal = 0)) as AppResult.Failure).error)
            val early = save(draft(due = startAt - 86_400_000L))
            assertEquals("还款日期不能早于借款日期", (early as AppResult.Failure).error.message)

            assertTrue(save(draft()) is AppResult.Success)
            val debt = debtRepo.items.values.single()
            assertEquals("小王", debt.counterparty)
            assertEquals(50_000, debt.principal)
            assertEquals("借款", debt.note)
            assertFalse(debt.settled)
        }

    @Test
    @DisplayName("部分还款：剩余减少、生成借贷流水、不结清")
    fun partialRepay() =
        runTest {
            val id = (save(draft()) as AppResult.Success).data

            val result = repay(id, 20_000, now) as AppResult.Success
            assertEquals(30_000, result.data.remaining)
            assertFalse(result.data.settled)

            val txn = txnRepo.items[result.data.txnId]!!
            assertEquals(TxnType.DEBT, txn.type)
            assertEquals(20_000, txn.amount)
            assertEquals(id, txn.debtId)
            assertFalse(debtRepo.items[id]!!.settled)
        }

    @Test
    @DisplayName("还满自动结清，结清后不能再还款")
    fun fullRepaySettles() =
        runTest {
            val id = (save(draft()) as AppResult.Success).data
            repay(id, 20_000, now)
            val last = repay(id, 30_000, now) as AppResult.Success

            assertEquals(0, last.data.remaining)
            assertTrue(last.data.settled)
            assertTrue(debtRepo.items[id]!!.settled)

            val again = repay(id, 1000, now)
            assertEquals("该笔借贷已结清", (again as AppResult.Failure).error.message)
        }

    @Test
    @DisplayName("还款金额超过剩余、为 0 都要拒绝")
    fun repayValidation() =
        runTest {
            val id = (save(draft()) as AppResult.Success).data
            assertEquals("还款金额不能超过剩余金额", (repay(id, 50_001, now) as AppResult.Failure).error.message)
            assertEquals(AppError.AmountNotPositive, (repay(id, 0, now) as AppResult.Failure).error)
            assertEquals(AppError.NotFound, (repay("missing", 100, now) as AppResult.Failure).error)
        }

    @Test
    @DisplayName("借入方向同样支持部分还款")
    fun borrowRepay() =
        runTest {
            val id = (save(draft(direction = DebtDirection.BORROW, principal = 100_000)) as AppResult.Success).data
            val result = repay(id, 40_000, now) as AppResult.Success
            assertEquals(60_000, result.data.remaining)
            assertEquals("还款：小王", txnRepo.items[result.data.txnId]!!.note)
        }

    @Test
    @DisplayName("坏账标记：结清台账、还款被拒绝")
    fun markBadDebt() =
        runTest {
            val id = (save(draft()) as AppResult.Success).data
            assertTrue(markBad.mark(id) is AppResult.Success)

            val debt = debtRepo.items[id]!!
            assertTrue(debt.writtenOff)
            assertTrue(debt.settled)
            assertEquals("该笔借贷已标记坏账", (repay(id, 1000, now) as AppResult.Failure).error.message)
        }

    @Test
    @DisplayName("逾期判定：未结清且过了还款日")
    fun overdue() {
        val debt =
            Debt(
                id = "d1",
                direction = DebtDirection.LEND,
                counterparty = "小王",
                principal = 50_000,
                startAt = startAt,
                dueAt = dueAt,
            )
        val afterDue = DateUtils.toMillis(LocalDate.of(2026, 12, 5), LocalTime.NOON)
        assertTrue(debt.isOverdue(afterDue))
        assertFalse(debt.isOverdue(now)) // 11/13 还没到 12/1
        assertFalse(debt.copy(settled = true).isOverdue(afterDue))
        assertFalse(debt.copy(writtenOff = true).isOverdue(afterDue))
        assertFalse(debt.copy(dueAt = null).isOverdue(afterDue))
    }
}
