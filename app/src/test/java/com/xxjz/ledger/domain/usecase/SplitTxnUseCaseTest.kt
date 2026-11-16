package com.xxjz.ledger.domain.usecase

import com.xxjz.ledger.core.common.AppResult
import com.xxjz.ledger.core.common.DateUtils
import com.xxjz.ledger.domain.model.Account
import com.xxjz.ledger.domain.model.AccountType
import com.xxjz.ledger.domain.model.Txn
import com.xxjz.ledger.domain.model.TxnType
import com.xxjz.ledger.testutil.FakeAccountRepository
import com.xxjz.ledger.testutil.FakeTxnRepository
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

/**
 * 拆分（v1.9.5 第 8 条）。
 *
 * 与旧实现的根本区别：不再是「等额 N 份」而是「各账户出多少」，所以合计校验是核心契约。
 */
@DisplayName("拆分流水")
class SplitTxnUseCaseTest {
    private val txnRepo = FakeTxnRepository()
    private val accountRepo = FakeAccountRepository()
    private val useCase = SplitTxnUseCase(txnRepo, accountRepo)
    private val now = DateUtils.toMillis(LocalDateTime.of(2026, 11, 16, 12, 0))

    private fun account(
        id: String,
        type: AccountType = AccountType.SAVINGS,
    ) {
        accountRepo.items[id] = Account(id = id, name = id, type = type)
    }

    private fun expense(
        id: String = "t1",
        amount: Long = 30_000,
    ) {
        txnRepo.items[id] =
            Txn(
                id = id,
                type = TxnType.EXPENSE,
                amount = amount,
                categoryId = "food",
                accountId = "a",
                dateTime = now,
                dateKey = DateUtils.dateKeyOf(now),
                note = "午饭",
                createdAt = now,
                updatedAt = now,
            )
    }

    @Test
    @DisplayName("两条明细合计正确时拆成两笔，原账进回收站")
    fun splitsIntoTwo() =
        runTest {
            expense()
            account("a")
            account("b")

            val result = useCase("t1", listOf(SplitPart("a", 20_000), SplitPart("b", 10_000)), now)

            val data = (result as AppResult.Success).data
            assertEquals(2, data.ids.size)
            assertEquals("t1", data.sourceId)
            assertTrue(txnRepo.items.getValue("t1").isDeleted, "原账必须进回收站，否则金额翻倍")
            assertEquals(20_000L, txnRepo.items.getValue(data.ids[0]).amount)
            assertEquals("a", txnRepo.items.getValue(data.ids[0]).accountId)
            assertEquals(10_000L, txnRepo.items.getValue(data.ids[1]).amount)
            assertEquals("b", txnRepo.items.getValue(data.ids[1]).accountId)
        }

    @Test
    @DisplayName("备注追加「拆分 i/n」，日后能认出它们本是一笔")
    fun noteMarksOrder() =
        runTest {
            expense()
            account("a")
            account("b")
            val data = (useCase("t1", listOf(SplitPart("a", 15_000), SplitPart("b", 15_000)), now) as AppResult.Success).data

            assertEquals("午饭（拆分 1/2）", txnRepo.items.getValue(data.ids[0]).note)
            assertEquals("午饭（拆分 2/2）", txnRepo.items.getValue(data.ids[1]).note)
        }

    @Test
    @DisplayName("合计少于原额被拒")
    fun rejectsShortSum() =
        runTest {
            expense()
            account("a")
            val result = useCase("t1", listOf(SplitPart("a", 10_000), SplitPart("a", 10_000)), now)

            assertEquals("明细合计必须等于原金额", (result as AppResult.Failure).error.message)
            assertFalse(txnRepo.items.getValue("t1").isDeleted)
        }

    @Test
    @DisplayName("合计超出原额被拒")
    fun rejectsOverSum() =
        runTest {
            expense()
            account("a")
            val result = useCase("t1", listOf(SplitPart("a", 20_000), SplitPart("a", 20_000)), now)

            assertEquals("明细合计必须等于原金额", (result as AppResult.Failure).error.message)
        }

    @Test
    @DisplayName("只有一条被拒")
    fun rejectsSinglePart() =
        runTest {
            expense()
            account("a")
            val result = useCase("t1", listOf(SplitPart("a", 30_000)), now)

            assertEquals("至少要两条明细", (result as AppResult.Failure).error.message)
        }

    @Test
    @DisplayName("超过 20 条被拒")
    fun rejectsTooManyParts() =
        runTest {
            expense(amount = 21_000)
            account("a")
            val parts = (1..21).map { SplitPart("a", 1_000) }
            val result = useCase("t1", parts, now)

            assertEquals("最多 20 条明细", (result as AppResult.Failure).error.message)
        }

    @Test
    @DisplayName("金额为 0 的明细被拒")
    fun rejectsZeroPart() =
        runTest {
            expense()
            account("a")
            val result = useCase("t1", listOf(SplitPart("a", 30_000), SplitPart("a", 0)), now)

            assertEquals("每条明细的金额都要大于 0", (result as AppResult.Failure).error.message)
        }

    @Test
    @DisplayName("投资账户不能作为拆分账户")
    fun rejectsInvestAccount() =
        runTest {
            expense()
            account("a")
            account("inv", AccountType.INVEST)
            val result = useCase("t1", listOf(SplitPart("a", 15_000), SplitPart("inv", 15_000)), now)

            assertEquals("inv 不能用于付款", (result as AppResult.Failure).error.message)
            assertFalse(txnRepo.items.getValue("t1").isDeleted)
        }

    @Test
    @DisplayName("账户不存在被拒")
    fun rejectsMissingAccount() =
        runTest {
            expense()
            account("a")
            val result = useCase("t1", listOf(SplitPart("a", 15_000), SplitPart("ghost", 15_000)), now)

            assertEquals("账户不存在", (result as AppResult.Failure).error.message)
        }

    @Test
    @DisplayName("同一账户重复出现是允许的")
    fun allowsSameAccountTwice() =
        runTest {
            expense()
            account("a")
            val data = (useCase("t1", listOf(SplitPart("a", 10_000), SplitPart("a", 20_000)), now) as AppResult.Success).data

            assertEquals(2, data.ids.size)
        }

    @Test
    @DisplayName("转账不能拆")
    fun rejectsTransfer() =
        runTest {
            txnRepo.items["t1"] =
                Txn(
                    id = "t1",
                    type = TxnType.TRANSFER,
                    amount = 10_000,
                    categoryId = null,
                    accountId = "a",
                    toAccountId = "b",
                    dateTime = now,
                    dateKey = DateUtils.dateKeyOf(now),
                    createdAt = now,
                    updatedAt = now,
                )
            account("a")
            val result = useCase("t1", listOf(SplitPart("a", 5_000), SplitPart("a", 5_000)), now)

            assertEquals("转账与借贷不能拆分", (result as AppResult.Failure).error.message)
        }

    @Test
    @DisplayName("原账不存在时返回 NotFound")
    fun rejectsMissingSource() =
        runTest {
            account("a")
            val result = useCase("nope", listOf(SplitPart("a", 5_000), SplitPart("a", 5_000)), now)
            assertEquals(com.xxjz.ledger.core.common.AppError.NotFound, (result as AppResult.Failure).error)
        }

    @Test
    @DisplayName("拆分不改变原账日期，也不复制删除标记")
    fun keepsDateAndClearsDeleted() =
        runTest {
            expense()
            account("a")
            account("b")
            val data = (useCase("t1", listOf(SplitPart("a", 15_000), SplitPart("b", 15_000)), now) as AppResult.Success).data

            val part = txnRepo.items.getValue(data.ids[0])
            assertEquals(DateUtils.dateKeyOf(now), part.dateKey)
            assertNull(part.deletedAt)
        }
}
