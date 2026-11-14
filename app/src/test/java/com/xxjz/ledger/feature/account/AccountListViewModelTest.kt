package com.xxjz.ledger.feature.account

import com.xxjz.ledger.data.datastore.SettingsRepository
import com.xxjz.ledger.domain.model.Account
import com.xxjz.ledger.domain.model.AccountType
import com.xxjz.ledger.domain.model.Txn
import com.xxjz.ledger.domain.model.TxnType
import com.xxjz.ledger.domain.usecase.DeleteAccountUseCase
import com.xxjz.ledger.domain.usecase.GetAssetSummaryUseCase
import com.xxjz.ledger.testutil.FakeAccountRepository
import com.xxjz.ledger.testutil.FakeSettingsDataStore
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
 * 账户页（v1.8.0 / PRD F3）：五分组、合计、入口卡、折叠持久化。
 *
 * 这些断言对应 PRD 第十节验收清单 10.4 的每一条，改坏了应该立刻红。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("账户页")
class AccountListViewModelTest {
    @BeforeEach
    fun setUp() {
        // 每个用例用自己 runTest 的调度器来当主调度器：把同一个 StandardTestDispatcher 复用给
        // 多个 runTest，后续用例会拿到「上一个用例已经跑完的调度器」，advanceUntilIdle 推不动任何东西。
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * 读当前状态。
     *
     * `stateIn(WhileSubscribed)` 的 `value` 在没人订阅时恒为初始值，直接读 `state.value`
     * 会永远拿到空状态；所以这里真实订阅一次，取「第一个非 loading 的快照」。
     */
    private suspend fun snapshotOf(model: AccountListViewModel): AccountListUiState = model.state.first { !it.loading }

    private fun account(
        id: String,
        type: AccountType,
        balance: Long = 0,
        market: Long = 0,
        cost: Long = 0,
        include: Boolean = true,
        hidden: Boolean = false,
    ) = Account(
        id = id,
        name = id,
        type = type,
        balance = balance,
        marketValueCents = market,
        costCents = cost,
        includeInNetAssets = include,
        hidden = hidden,
    )

    private fun vm(
        repo: FakeAccountRepository,
        txnRepo: FakeTxnRepository = FakeTxnRepository(),
        settings: SettingsRepository = FakeSettingsDataStore(),
    ) = AccountListViewModel(
        repository = repo,
        txnRepository = txnRepo,
        deleteAccount = DeleteAccountUseCase(repo),
        summaryUseCase = GetAssetSummaryUseCase(repo),
        settings = settings,
    )

    @Test
    @DisplayName("五类账户按 现金→储蓄卡→信用→借贷→投资 顺序分组，空组不出现")
    fun groupsOrderedAndEmptySkipped() =
        runTest {
            val repo = FakeAccountRepository()
            repo.items["i"] = account("i", AccountType.INVEST, market = 1_000_00)
            repo.items["c"] = account("c", AccountType.CASH, balance = 100_00)
            repo.items["cr"] = account("cr", AccountType.CREDIT, balance = -200_00)
            // 没有储蓄卡与借贷：这两组不应出现
            val model = vm(repo)
            advanceUntilIdle()

            val types = snapshotOf(model).groups.map { it.type }
            assertEquals(listOf(AccountType.CASH, AccountType.CREDIT, AccountType.INVEST), types)
            assertEquals(listOf("现金", "信用账户", "投资"), snapshotOf(model).groups.map { it.title })
        }

    @Test
    @DisplayName("投资组按市值合计，其它组按余额合计")
    fun groupTotals() =
        runTest {
            val repo = FakeAccountRepository()
            repo.items["c1"] = account("c1", AccountType.CASH, balance = 100_00)
            repo.items["c2"] = account("c2", AccountType.CASH, balance = 50_00)
            // 投资账户余额 300（转入净额）但市值 500，组合计必须是 500 而不是 300 或 800
            repo.items["i1"] = account("i1", AccountType.INVEST, balance = 300_00, market = 500_00, cost = 300_00)
            val model = vm(repo)
            advanceUntilIdle()

            val groups = snapshotOf(model).groups
            assertEquals(150_00, groups.first { it.type == AccountType.CASH }.total)
            assertEquals(500_00, groups.first { it.type == AccountType.INVEST }.total)
        }

    @Test
    @DisplayName("隐藏账户不进分组，只出现在隐藏区")
    fun hiddenExcluded() =
        runTest {
            val repo = FakeAccountRepository()
            repo.items["c"] = account("c", AccountType.CASH, balance = 100_00)
            repo.items["h"] = account("h", AccountType.CASH, balance = 999_00, hidden = true)
            val model = vm(repo)
            advanceUntilIdle()

            val state = snapshotOf(model)
            assertEquals(listOf("c"), state.groups.single().accounts.map { it.id })
            assertEquals(listOf("h"), state.hidden.map { it.id })
        }

    @Test
    @DisplayName("折叠状态写进 DataStore，重建 ViewModel 后仍保持")
    fun collapsePersisted() =
        runTest {
            val repo = FakeAccountRepository()
            repo.items["c"] = account("c", AccountType.CASH, balance = 1_00)
            val settings = FakeSettingsDataStore()
            val model = vm(repo, settings = settings)
            advanceUntilIdle()

            model.toggleGroup(AccountType.CASH)
            advanceUntilIdle()
            assertTrue(snapshotOf(model).groups.single().collapsed)

            // 新建一个 ViewModel 模拟重新进入页面，折叠状态应从偏好里读回来
            val again = vm(repo, settings = settings)
            advanceUntilIdle()
            assertTrue(snapshotOf(again).groups.single().collapsed, "折叠状态必须持久化")

            again.toggleGroup(AccountType.CASH)
            advanceUntilIdle()
            assertFalse(snapshotOf(again).groups.single().collapsed)
        }

    @Test
    @DisplayName("待报销入口卡统计待报销笔数与未报金额")
    fun reimbursementCard() =
        runTest {
            val repo = FakeAccountRepository()
            repo.items["c"] = account("c", AccountType.CASH, balance = 0)
            val txnRepo = FakeTxnRepository()
            val now = 1_700_000_000_000L
            txnRepo.items["t1"] =
                Txn(
                    id = "t1",
                    type = TxnType.EXPENSE,
                    amount = 200_00,
                    categoryId = null,
                    accountId = "c",
                    dateTime = now,
                    dateKey = 20261114,
                    pendingReimbursement = true,
                    reimbursedAmount = 50_00,
                    createdAt = now,
                    updatedAt = now,
                )
            val model = vm(repo, txnRepo = txnRepo)
            advanceUntilIdle()

            val card = snapshotOf(model).reimbursement
            assertEquals(1, card.count)
            assertEquals(150_00, card.amountCents, "未报金额 = 金额 − 已报")
        }

    @Test
    @DisplayName("净资产总览走统一口径：投资按市值、计净开关生效")
    fun summaryUsesUnifiedRule() =
        runTest {
            val repo = FakeAccountRepository()
            repo.items["c"] = account("c", AccountType.CASH, balance = 1_000_00)
            repo.items["i"] = account("i", AccountType.INVEST, market = 2_000_00, include = false)
            val model = vm(repo)
            advanceUntilIdle()

            val summary = snapshotOf(model).summary
            assertEquals(1_000_00, summary.assets, "不计入净资产的投资市值不进总资产")
            assertEquals(2_000_00, summary.investExcluded)
        }
}
