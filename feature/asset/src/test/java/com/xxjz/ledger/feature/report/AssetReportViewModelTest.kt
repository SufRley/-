package com.xxjz.ledger.feature.report

import com.xxjz.ledger.data.local.MetaDaoFakeForReport
import com.xxjz.ledger.data.repository.AssetEventStore
import com.xxjz.ledger.domain.model.Account
import com.xxjz.ledger.domain.model.AccountType
import com.xxjz.ledger.domain.model.Txn
import com.xxjz.ledger.domain.model.TxnType
import com.xxjz.ledger.domain.usecase.AssetChangeKind
import com.xxjz.ledger.domain.usecase.GetAssetBreakdownUseCase
import com.xxjz.ledger.domain.usecase.GetAssetChangesUseCase
import com.xxjz.ledger.domain.usecase.GetLiabilityBreakdownUseCase
import com.xxjz.ledger.domain.usecase.GetNetWorthTrendUseCase
import com.xxjz.ledger.domain.usecase.TrendPeriod
import com.xxjz.ledger.domain.usecase.TrendPoint
import com.xxjz.ledger.testutil.FakeAccountRepository
import com.xxjz.ledger.testutil.FakeCategoryRepository
import com.xxjz.ledger.testutil.FakeTxnRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 资产报表页 ViewModel（PRD 5.2 / 验收清单 10.6）。
 *
 * 只验「四个板块的组装与切换」，纯计算与回放已在
 * [com.xxjz.ledger.domain.usecase.AssetBreakdownTest] 与 NetWorthTrendTest 覆盖。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("资产报表页")
class AssetReportViewModelTest {
    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private suspend fun snapshotOf(model: AssetReportViewModel): AssetReportUiState = model.state.first { !it.loading }

    private fun account(
        id: String,
        type: AccountType,
        balance: Long = 0,
        market: Long = 0,
        include: Boolean = true,
    ) = Account(
        id = id,
        name = id,
        type = type,
        initialBalance = balance,
        balance = balance,
        marketValueCents = market,
        includeInNetAssets = include,
    )

    private fun txn(
        id: String,
        type: TxnType,
        amount: Long,
        at: Long,
    ) = Txn(
        id = id,
        type = type,
        amount = amount,
        categoryId = null,
        accountId = "cash",
        dateTime = at,
        dateKey = com.xxjz.ledger.core.common.DateUtils.dateKeyOf(at),
        createdAt = at,
        updatedAt = at,
    )

    private fun model(
        accounts: List<Account> = emptyList(),
        txns: List<Txn> = emptyList(),
    ): AssetReportViewModel {
        val accountRepo = FakeAccountRepository()
        accounts.forEach { accountRepo.items[it.id] = it }
        val txnRepo = FakeTxnRepository()
        txns.forEach { txnRepo.put(it, notify = false) }
        val categoryRepo = FakeCategoryRepository()
        val store = AssetEventStore(MetaDaoFakeForReport())
        return AssetReportViewModel(
            assetBreakdown = GetAssetBreakdownUseCase(accountRepo),
            liabilityBreakdown = GetLiabilityBreakdownUseCase(accountRepo),
            netWorthTrend = GetNetWorthTrendUseCase(accountRepo, txnRepo, store),
            assetChanges = GetAssetChangesUseCase(accountRepo, txnRepo, categoryRepo, store),
            accountRepository = accountRepo,
        )
    }

    @Test
    fun `四个板块都能拿到数据`() =
        runTest {
            val vm = model(listOf(account("cash", AccountType.CASH, balance = 1_000_00)))
            val state = snapshotOf(vm)
            assertFalse(state.loading)
            assertEquals(1_000_00L, state.assets.total)
            assertEquals(0L, state.liability.total)
            assertEquals(1_000_00L, state.net, "净资产 = 资产 − 负债")
            assertTrue(state.trend.points.isNotEmpty(), "趋势默认按月，应有 12 个点")
            assertEquals(12, state.trend.points.size)
        }

    @Test
    fun `净资产扣掉负债`() =
        runTest {
            val vm =
                model(
                    listOf(
                        account("cash", AccountType.CASH, balance = 5_000_00),
                        account("credit", AccountType.CREDIT, balance = -1_200_00),
                    ),
                )
            val state = snapshotOf(vm)
            assertEquals(5_000_00L, state.assets.total)
            assertEquals(1_200_00L, state.liability.total)
            assertEquals(3_800_00L, state.net)
        }

    @Test
    fun `默认粒度是按月`() =
        runTest {
            val state = snapshotOf(model(listOf(account("cash", AccountType.CASH, balance = 100_00))))
            assertEquals(TrendPeriod.MONTH, state.period)
        }

    @Test
    fun `切换到按年后趋势变成五年`() =
        runTest {
            val vm = model(listOf(account("cash", AccountType.CASH, balance = 100_00)))
            snapshotOf(vm)
            vm.setPeriod(TrendPeriod.YEAR)
            advanceUntilIdle()
            val state = vm.state.first { it.period == TrendPeriod.YEAR && !it.loading }
            assertEquals(5, state.trend.points.size)
            assertEquals(TrendPeriod.YEAR, state.trend.period)
        }

    @Test
    fun `切回按月能恢复十二个点`() =
        runTest {
            val vm = model(listOf(account("cash", AccountType.CASH, balance = 100_00)))
            vm.setPeriod(TrendPeriod.YEAR)
            advanceUntilIdle()
            vm.setPeriod(TrendPeriod.MONTH)
            advanceUntilIdle()
            val state = vm.state.first { it.period == TrendPeriod.MONTH && !it.loading }
            assertEquals(12, state.trend.points.size)
        }

    @Test
    fun `变化记录进入状态且排除转账`() =
        runTest {
            val now = System.currentTimeMillis()
            val vm =
                model(
                    listOf(account("cash", AccountType.CASH, balance = 1_000_00)),
                    listOf(
                        txn("t1", TxnType.EXPENSE, 100_00, now - 10_000),
                        txn("t2", TxnType.TRANSFER, 200_00, now - 5_000),
                    ),
                )
            val state = snapshotOf(vm)
            assertEquals(1, state.changes.size, "转账不进变化记录")
            assertEquals(AssetChangeKind.EXPENSE, state.changes.first().kind)
            assertEquals(-100_00L, state.changes.first().delta)
        }

    @Test
    fun `空账号时不崩且各板块为空`() =
        runTest {
            val state = snapshotOf(model())
            assertEquals(0L, state.net)
            assertTrue(state.assets.isEmpty)
            assertTrue(state.liability.isEmpty)
            assertTrue(state.changes.isEmpty())
        }

    @Test
    fun `标签抽样最多四个且首尾正确`() {
        val points = (1..12).map { TrendPoint("${it}月", it.toLong(), 0) }
        val sample = trendLabelSample(points)
        assertEquals(4, sample.size)
        assertEquals("1月", sample.first())
        assertEquals("12月", sample.last())
    }

    @Test
    fun `标签抽样点数不足时全给`() {
        val points = listOf(TrendPoint("2025", 1, 0), TrendPoint("2026", 2, 0))
        assertEquals(listOf("2025", "2026"), trendLabelSample(points))
        assertTrue(trendLabelSample(emptyList()).isEmpty())
    }
}
