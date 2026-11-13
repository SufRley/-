package com.xxjz.ledger.domain.usecase

import com.xxjz.ledger.core.common.AppError
import com.xxjz.ledger.core.common.AppResult
import com.xxjz.ledger.data.local.seed.SeedData
import com.xxjz.ledger.domain.model.Txn
import com.xxjz.ledger.domain.model.TxnType
import com.xxjz.ledger.testutil.FakeCategoryRepository
import com.xxjz.ledger.testutil.FakeTxnRepository
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** M2 账目维护用例：退款、不计入收支、复制（AC-10 / AC-11 / AC-12 / AC-15）。 */
@DisplayName("账目维护用例")
class TxnCareUseCasesTest {
    private val repo = FakeTxnRepository()
    private val categoryRepo = FakeCategoryRepository()
    private val refund = RefundTxnUseCase(repo, categoryRepo)
    private val toggle = ToggleExcludeUseCase(repo)
    private val duplicate = DuplicateTxnUseCase(repo, categoryRepo)

    private fun expense(
        id: String = "e1",
        amount: Long = 10_000,
        note: String = "买鞋",
    ) = Txn(
        id = id,
        type = TxnType.EXPENSE,
        amount = amount,
        categoryId = "exp_2_1",
        accountId = "acc-1",
        dateTime = 1_700_000_000_000L,
        dateKey = 20231114,
        note = note,
        createdAt = 1_700_000_000_000L,
        updatedAt = 1_700_000_000_000L,
    ).also { repo.items[id] = it }

    @Test
    fun `部分退款生成退款收入并关联原账目`() =
        runTest {
            expense()
            val r = refund("e1", 3_000, now = 1_700_000_100_000L)
            assertTrue(r is AppResult.Success<String>)
            val refundTxn = repo.byId((r as AppResult.Success<String>).data)!!
            assertEquals(TxnType.INCOME, refundTxn.type)
            assertEquals(3_000L, refundTxn.amount)
            assertEquals("e1", refundTxn.refundOfTxnId)
            assertEquals(SeedData.REFUND_CATEGORY_ID, refundTxn.categoryId)
            assertEquals("acc-1", refundTxn.accountId)
            assertEquals(3_000L, repo.refundedTotal("e1"))
        }

    @Test
    fun `累计退款不得超过原支出`() =
        runTest {
            expense(amount = 5_000)
            assertTrue(refund("e1", 4_000) is AppResult.Success<String>)
            val second = refund("e1", 1_500)
            assertTrue(second is AppResult.Failure && second.error == AppError.RefundExceedsOriginal)
            val exact = refund("e1", 1_000)
            assertTrue(exact is AppResult.Success<String>)
            assertEquals(5_000L, repo.refundedTotal("e1"))
        }

    @Test
    fun `收入不能退款`() =
        runTest {
            repo.items["i1"] =
                expense(id = "i1").copy(type = TxnType.INCOME, categoryId = "inc_0_0")
            val r = refund("i1", 100)
            assertTrue(r is AppResult.Failure && r.error == AppError.Custom("只有支出可以退款"))
        }

    @Test
    fun `退款金额必须大于0`() =
        runTest {
            expense()
            val r = refund("e1", 0)
            assertTrue(r is AppResult.Failure && r.error == AppError.AmountNotPositive)
        }

    @Test
    fun `退款失败不写入流水`() =
        runTest {
            expense(amount = 1_000)
            refund("e1", 2_000)
            assertEquals(1, repo.items.size)
            assertTrue(repo.items.values.none { it.refundOfTxnId != null })
        }

    @Test
    fun `不计入收支开关翻转并即时影响统计`() =
        runTest {
            expense(amount = 8_800)
            val first = toggle("e1", now = 2_000L)
            assertEquals(true, (first as AppResult.Success<Boolean>).data)
            assertTrue(repo.byId("e1")!!.excludedFromStats)
            assertEquals(0L, repo.totals(0, Long.MAX_VALUE).second)
            val second = toggle("e1", now = 3_000L)
            assertEquals(false, (second as AppResult.Success<Boolean>).data)
            assertEquals(8_800L, repo.totals(0, Long.MAX_VALUE).second)
        }

    @Test
    fun `切换不存在的账目返回NotFound`() =
        runTest {
            val r = toggle("missing")
            assertTrue(r is AppResult.Failure && r.error == AppError.NotFound)
        }

    @Test
    fun `复制账目重置时间并清空退款与报销状态`() =
        runTest {
            expense().also {
                repo.items["e1"] =
                    it.copy(refundOfTxnId = "origin", pendingReimbursement = true, reimbursedAmount = 500)
            }
            val now = 1_800_000_000_000L
            val r = duplicate("e1", now)
            assertTrue(r is AppResult.Success<String>)
            val copy = repo.byId((r as AppResult.Success<String>).data)!!
            assertTrue(copy.id != "e1")
            assertEquals(now, copy.dateTime)
            assertEquals(10_000L, copy.amount)
            assertEquals("买鞋", copy.note)
            assertNull(copy.refundOfTxnId)
            assertEquals(false, copy.pendingReimbursement)
            assertEquals(0L, copy.reimbursedAmount)
            assertNotNull(copy.createdAt)
        }

    @Test
    fun `复制不存在的账目返回NotFound`() =
        runTest {
            val r = duplicate("missing")
            assertTrue(r is AppResult.Failure && r.error == AppError.NotFound)
        }
}
