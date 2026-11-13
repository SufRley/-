package com.xxjz.ledger.domain.usecase

import com.xxjz.ledger.core.common.AppError
import com.xxjz.ledger.core.common.AppResult
import com.xxjz.ledger.core.common.DateUtils
import com.xxjz.ledger.domain.model.TxnType
import com.xxjz.ledger.testutil.FakeCategoryRepository
import com.xxjz.ledger.testutil.FakeTxnRepository
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** M2 保存用例测试：金额、分类、账户、转账同账户、编辑保留创建时间（AC-02 / AC-04 / AC-15）。 */
@DisplayName("保存账目用例")
class SaveTxnUseCaseTest {
    private val txnRepo = FakeTxnRepository()
    private val categoryRepo = FakeCategoryRepository()
    private val useCase = SaveTxnUseCase(txnRepo, categoryRepo)

    private fun draft(
        type: TxnType = TxnType.EXPENSE,
        amount: Long = 1000,
        categoryId: String? = "exp_0_0",
        accountId: String? = "acc-1",
        toAccountId: String? = null,
        id: String? = null,
    ) = TxnDraft(
        id = id,
        type = type,
        amountCents = amount,
        categoryId = categoryId,
        accountId = accountId,
        toAccountId = toAccountId,
        dateTime = 1_700_000_000_000L,
        note = "  午饭  ",
    )

    @Test
    fun `金额为0被拒绝`() =
        runTest {
            val r = useCase(draft(amount = 0))
            assertTrue(r is AppResult.Failure && r.error == AppError.AmountNotPositive)
        }

    @Test
    fun `超出9位上限被拒绝`() =
        runTest {
            val r = useCase(draft(amount = 100_000_000_000L))
            assertTrue(r is AppResult.Failure && r.error == AppError.AmountTooLarge)
        }

    @Test
    fun `支出缺少分类被拒绝`() =
        runTest {
            val r = useCase(draft(categoryId = null))
            assertTrue(r is AppResult.Failure && r.error == AppError.CategoryRequired)
        }

    @Test
    fun `缺少账户被拒绝`() =
        runTest {
            val r = useCase(draft(accountId = null))
            assertTrue(r is AppResult.Failure && r.error == AppError.AccountRequired)
        }

    @Test
    fun `转账两账户相同被拒绝`() =
        runTest {
            val r = useCase(draft(type = TxnType.TRANSFER, categoryId = null, toAccountId = "acc-1"))
            assertTrue(r is AppResult.Failure && r.error == AppError.SameAccount)
        }

    @Test
    fun `新建成功并写入正确字段`() =
        runTest {
            val r = useCase(draft())
            assertTrue(r is AppResult.Success)
            val id = (r as AppResult.Success<String>).data
            val saved = txnRepo.byId(id)
            assertNotNull(saved)
            assertEquals(1000L, saved!!.amount)
            assertEquals("午饭", saved.note)
            assertEquals(TxnType.EXPENSE, saved.type)
            assertEquals(DateUtils.dateKeyOf(1_700_000_000_000L), saved.dateKey)
            assertEquals(1, categoryRepo.usage["exp_0_0"])
        }

    @Test
    fun `编辑保留id与创建时间并刷新更新时间`() =
        runTest {
            val first = useCase(draft()) as AppResult.Success<String>
            val original = txnRepo.byId(first.data)!!
            val edited =
                useCase(
                    draft(amount = 2500, id = original.id).copy(note = "改过的备注"),
                    now = original.createdAt + 10_000,
                )
            assertTrue(edited is AppResult.Success)
            val second = txnRepo.byId(original.id)!!
            assertEquals(original.createdAt, second.createdAt)
            assertEquals(original.createdAt + 10_000, second.updatedAt)
            assertEquals(2500L, second.amount)
            assertEquals("改过的备注", second.note)
        }

    @Test
    fun `换分类才累加使用次数`() =
        runTest {
            val created = useCase(draft()) as AppResult.Success<String>
            assertEquals(1, categoryRepo.usage["exp_0_0"])
            useCase(draft(id = created.data))
            assertEquals(1, categoryRepo.usage["exp_0_0"])
            useCase(draft(id = created.data, categoryId = "exp_1_0"))
            assertEquals(1, categoryRepo.usage["exp_1_0"])
        }

    @Test
    fun `编辑不存在的账目返回NotFound`() =
        runTest {
            val r = useCase(draft(id = "missing"))
            assertTrue(r is AppResult.Failure && r.error == AppError.NotFound)
        }
}
