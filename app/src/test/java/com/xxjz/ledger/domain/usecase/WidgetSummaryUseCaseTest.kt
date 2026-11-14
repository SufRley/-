package com.xxjz.ledger.domain.usecase

import com.xxjz.ledger.core.common.DateUtils
import com.xxjz.ledger.domain.model.Account
import com.xxjz.ledger.domain.model.AccountType
import com.xxjz.ledger.domain.model.Txn
import com.xxjz.ledger.domain.model.TxnType
import com.xxjz.ledger.testutil.FakeAccountRepository
import com.xxjz.ledger.testutil.FakeTxnRepository
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** M9-A 小组件取数（AC-83）：只算本月、排除软删、净资产的符号。 */
@DisplayName("小组件取数")
class WidgetSummaryUseCaseTest {
    private val txnRepo = FakeTxnRepository()
    private val accountRepo = FakeAccountRepository()
    private val useCase = GetWidgetSummaryUseCase(txnRepo, accountRepo, GetAssetSummaryUseCase(accountRepo))

    private val thisMonth = LocalDate.of(2026, 11, 13)

    private fun txn(
        id: String,
        date: LocalDate,
        amount: Long,
        type: TxnType = TxnType.EXPENSE,
        deletedAt: Long? = null,
        excluded: Boolean = false,
    ) = Txn(
        id = id,
        type = type,
        amount = amount,
        categoryId = "c1",
        accountId = "a1",
        dateTime = DateUtils.toMillis(date, java.time.LocalTime.NOON),
        dateKey = DateUtils.dateKeyOf(date),
        deletedAt = deletedAt,
        excludedFromStats = excluded,
        createdAt = 1,
        updatedAt = 1,
    )

    @Test
    @DisplayName("只统计本月，上月与下月不串进来")
    fun monthOnly() =
        runTest {
            txnRepo.put(txn("in1", thisMonth, 500_00, TxnType.INCOME))
            txnRepo.put(txn("out1", thisMonth, 120_00))
            txnRepo.put(txn("prev", LocalDate.of(2026, 10, 31), 999_00))
            txnRepo.put(txn("next", LocalDate.of(2026, 12, 1), 888_00))

            val s = useCase(thisMonth)
            assertEquals(500_00, s.income)
            assertEquals(120_00, s.expense)
            assertEquals(2, s.count)
        }

    @Test
    @DisplayName("软删除的记录不算进收支与笔数")
    fun deletedIgnored() =
        runTest {
            txnRepo.put(txn("out1", thisMonth, 120_00))
            txnRepo.put(txn("gone", thisMonth, 300_00, deletedAt = 1L))

            val s = useCase(thisMonth)
            assertEquals(120_00, s.expense)
            assertEquals(1, s.count)
        }

    @Test
    @DisplayName("转账与借贷算笔数但不进收支")
    fun transferCounted() =
        runTest {
            txnRepo.put(txn("out1", thisMonth, 120_00))
            txnRepo.put(txn("mv", thisMonth, 1_000_00, TxnType.TRANSFER))

            val s = useCase(thisMonth)
            assertEquals(120_00, s.expense)
            assertEquals(0, s.income)
            assertEquals(2, s.count)
        }

    @Test
    @DisplayName("抵消统计的记录既不进收支也不影响净资产口径")
    fun excluded() =
        runTest {
            txnRepo.put(txn("out1", thisMonth, 120_00))
            txnRepo.put(txn("big", thisMonth, 50_000_00, excluded = true))

            assertEquals(120_00, useCase(thisMonth).expense)
        }

    @Test
    @DisplayName("净资产来自账户余额，信用卡欠款体现为负债")
    fun netAssets() =
        runTest {
            accountRepo.upsert(Account(id = "cash", name = "现金", type = AccountType.SAVINGS, balance = 2_000_00))
            accountRepo.upsert(Account(id = "card", name = "信用卡", type = AccountType.CREDIT, balance = -800_00))

            assertEquals(1_200_00, useCase(thisMonth).netAssets)
        }

    @Test
    @DisplayName("没有任何数据时是空状态，不会因为资产为 0 就报错")
    fun empty() =
        runTest {
            val s = useCase(thisMonth)
            assertEquals(true, s.isEmpty)
            assertEquals(0, s.count)
        }
}
