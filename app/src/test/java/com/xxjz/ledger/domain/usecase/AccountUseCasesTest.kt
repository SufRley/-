package com.xxjz.ledger.domain.usecase

import com.xxjz.ledger.core.common.AppError
import com.xxjz.ledger.core.common.AppResult
import com.xxjz.ledger.domain.model.Account
import com.xxjz.ledger.domain.model.AccountType
import com.xxjz.ledger.testutil.FakeAccountRepository
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** M3 账户用例：资产汇总口径、删除级联与「至少保留一个账户」（AC-19 / AC-22）。 */
@DisplayName("账户用例")
class AccountUseCasesTest {
    private val repo = FakeAccountRepository()
    private val summary = GetAssetSummaryUseCase(repo)
    private val delete = DeleteAccountUseCase(repo)

    private fun account(
        id: String,
        balance: Long,
        type: AccountType = AccountType.FUND,
        hidden: Boolean = false,
    ) = Account(id = id, name = id, type = type, balance = balance, hidden = hidden)

    @Test
    fun `正余额计入资产负余额计入负债`() {
        val s =
            summary(
                listOf(
                    account("cash", 1_000_00),
                    account("card", -3_200_00, AccountType.CREDIT),
                    account("overdraft", -50_00),
                ),
            )
        assertEquals(100_000L, s.assets)
        assertEquals(325_000L, s.liabilities)
        assertEquals(-225_000L, s.net)
    }

    @Test
    fun `隐藏账户不计入汇总`() {
        val s = summary(listOf(account("cash", 500_00), account("old", 999_00, hidden = true)))
        assertEquals(50_000L, s.assets)
        assertEquals(50_000L, s.net)
    }

    @Test
    fun `零余额账户不影响两端`() {
        val s = summary(listOf(account("empty", 0)))
        assertEquals(0L, s.assets)
        assertEquals(0L, s.liabilities)
        assertEquals(0L, s.net)
    }

    @Test
    fun `删除账户返回连带删除的笔数`() =
        runTest {
            repo.items["a"] = account("a", 0)
            repo.items["b"] = account("b", 0)
            repo.txnCounts["a"] = 7
            val r = delete("a", now = 1_000L)
            assertTrue(r is AppResult.Success<Int>)
            assertEquals(7, (r as AppResult.Success<Int>).data)
            // M8 起改为软删除：账户进回收站，行还在，但不再出现在正常列表里
            assertEquals(1_000L, repo.deletedAt["a"])
            assertTrue(repo.items.containsKey("a"))
            assertEquals(1, repo.count())
        }

    @Test
    fun `回收站还原账户后重新出现在列表`() =
        runTest {
            repo.items["a"] = account("a", 0)
            repo.items["b"] = account("b", 0)
            delete("a", now = 2_000L)
            repo.restore("a", at = 3_000L)
            assertTrue("a" !in repo.deletedAt)
            assertEquals(2, repo.count())
        }

    @Test
    fun `仅剩一个账户时禁止删除`() =
        runTest {
            repo.items["only"] = account("only", 0)
            val r = delete("only")
            assertTrue(r is AppResult.Failure && r.error == AppError.Custom("至少保留一个账户"))
            assertTrue(repo.items.containsKey("only"))
        }

    @Test
    fun `删除不存在的账户返回NotFound`() =
        runTest {
            repo.items["a"] = account("a", 0)
            repo.items["b"] = account("b", 0)
            val r = delete("missing")
            assertTrue(r is AppResult.Failure && r.error == AppError.NotFound)
        }
}
