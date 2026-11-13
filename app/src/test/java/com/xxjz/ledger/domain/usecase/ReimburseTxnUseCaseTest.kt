package com.xxjz.ledger.domain.usecase

import com.xxjz.ledger.core.common.AppError
import com.xxjz.ledger.core.common.AppResult
import com.xxjz.ledger.core.common.DateUtils
import com.xxjz.ledger.data.local.seed.SeedData
import com.xxjz.ledger.domain.model.Txn
import com.xxjz.ledger.domain.model.TxnType
import com.xxjz.ledger.testutil.FakeCategoryRepository
import com.xxjz.ledger.testutil.FakeTxnRepository
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

/** M7 待报销：确认、差额、撤销、边界（AC-59 ~ AC-62）。 */
@DisplayName("报销用例")
class ReimburseTxnUseCaseTest {
    private val txnRepo = FakeTxnRepository()
    private val categoryRepo = FakeCategoryRepository()
    private val useCase = ReimburseTxnUseCase(txnRepo, categoryRepo)
    private val now = DateUtils.toMillis(LocalDate.of(2026, 11, 13), LocalTime.NOON)

    private fun expense(
        id: String,
        amount: Long,
        pending: Boolean = true,
        deletedAt: Long? = null,
    ): Txn {
        val txn =
            Txn(
                id = id,
                type = TxnType.EXPENSE,
                amount = amount,
                categoryId = "exp_1_0",
                accountId = "acc-1",
                dateTime = now - 86_400_000L,
                dateKey = 20261112,
                note = "打车",
                pendingReimbursement = pending,
                deletedAt = deletedAt,
                createdAt = now,
                updatedAt = now,
            )
        txnRepo.items[id] = txn
        return txn
    }

    private fun pendingExpense(amount: Long = 12_000): Txn = expense("t1", amount)

    @Test
    @DisplayName("全额报销：生成同额收入，原账目净支出归零")
    fun fullReimbursement() =
        runTest {
            pendingExpense(12_000)
            val result = useCase.confirm("t1", 12_000, now)

            assertTrue(result is AppResult.Success)
            val data = (result as AppResult.Success).data
            assertEquals(12_000, data.reimbursed)
            assertEquals(0, data.selfPaid)

            val original = txnRepo.items["t1"]!!
            assertFalse(original.pendingReimbursement)
            assertEquals(12_000, original.reimbursedAmount)
            assertEquals(now, original.reimbursedAt)

            val income = txnRepo.items[data.incomeTxnId]!!
            assertEquals(TxnType.INCOME, income.type)
            assertEquals(12_000, income.amount)
            assertEquals(SeedData.REIMBURSE_CATEGORY_ID, income.categoryId)
            assertEquals("t1", income.parentTxnId)
            assertEquals("acc-1", income.accountId)
        }

    @Test
    @DisplayName("部分报销：差额就是自付，不再额外生成流水")
    fun partialReimbursement() =
        runTest {
            pendingExpense(12_000)
            val result = useCase.confirm("t1", 9_000, now) as AppResult.Success
            assertEquals(9_000, result.data.reimbursed)
            assertEquals(3_000, result.data.selfPaid)

            // 只有一条收入，且金额等于实报
            val incomes = txnRepo.items.values.filter { it.type == TxnType.INCOME }
            assertEquals(1, incomes.size)
            assertEquals(9_000, incomes.single().amount)
        }

    @Test
    @DisplayName("报销金额超过支出、为 0、非待报销状态都要拒绝")
    fun rejectsInvalidAmounts() =
        runTest {
            pendingExpense(12_000)
            assertEquals("报销金额不能超过支出金额", (useCase.confirm("t1", 12_001, now) as AppResult.Failure).error.message)
            assertEquals(AppError.AmountNotPositive, (useCase.confirm("t1", 0, now) as AppResult.Failure).error)

            useCase.confirm("t1", 12_000, now)
            assertEquals("该账目不在待报销状态", (useCase.confirm("t1", 5_000, now) as AppResult.Failure).error.message)
        }

    @Test
    @DisplayName("收入账目不能报销")
    fun rejectsIncome() =
        runTest {
            txnRepo.items["i1"] =
                Txn(
                    id = "i1",
                    type = TxnType.INCOME,
                    amount = 1000,
                    categoryId = "inc_0_0",
                    accountId = "acc-1",
                    dateTime = now,
                    dateKey = 20261113,
                    pendingReimbursement = true,
                    createdAt = now,
                    updatedAt = now,
                )
            assertEquals("只有支出可以报销", (useCase.confirm("i1", 500, now) as AppResult.Failure).error.message)
        }

    @Test
    @DisplayName("撤销报销：删掉收入、原账目回到待报销")
    fun undoRestoresState() =
        runTest {
            pendingExpense(12_000)
            val data = (useCase.confirm("t1", 7_000, now) as AppResult.Success).data

            val undo = useCase.undo("t1", now)
            assertTrue(undo is AppResult.Success)

            assertNull(txnRepo.items[data.incomeTxnId])
            val original = txnRepo.items["t1"]!!
            assertTrue(original.pendingReimbursement)
            assertEquals(0, original.reimbursedAmount)
            assertNull(original.reimbursedAt)
        }

    @Test
    @DisplayName("未报销的账目撤销时报错，不产生副作用")
    fun undoWithoutReimbursement() =
        runTest {
            pendingExpense(12_000)
            val undo = useCase.undo("t1", now)
            assertEquals("该账目未报销", (undo as AppResult.Failure).error.message)
            assertTrue(txnRepo.items["t1"]!!.pendingReimbursement)
        }

    @Test
    @DisplayName("待报销合计：只算未删除的待报销支出")
    fun pendingTotal() =
        runTest {
            expense("t1", 12_000)
            expense("t2", 3_000, pending = false)
            expense("t3", 5_000, deletedAt = now)
            assertEquals(12_000, useCase.pendingTotal())
        }
}
