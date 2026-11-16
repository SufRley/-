package com.xxjz.ledger.feature.trash

import com.xxjz.ledger.data.datastore.SettingsDataStore
import com.xxjz.ledger.data.local.TestDatabaseFactory
import com.xxjz.ledger.data.local.db.AppDatabase
import com.xxjz.ledger.domain.model.Account
import com.xxjz.ledger.domain.model.AccountType
import com.xxjz.ledger.domain.model.AppSettings
import com.xxjz.ledger.domain.model.Budget
import com.xxjz.ledger.domain.model.BudgetScope
import com.xxjz.ledger.domain.model.Txn
import com.xxjz.ledger.domain.model.TxnType
import com.xxjz.ledger.domain.usecase.TrashCleanupUseCase
import com.xxjz.ledger.testutil.FakeAccountRepository
import com.xxjz.ledger.testutil.FakeBudgetRepository
import com.xxjz.ledger.testutil.FakeTxnRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** M8 回收站（AC-34、AC-50、AC-76）。 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class TrashViewModelTest {
    private val txnRepo = FakeTxnRepository()
    private val accountRepo = FakeAccountRepository()
    private val budgetRepo = FakeBudgetRepository()
    private val settingsFlow = MutableStateFlow(AppSettings(recycleBinDays = 30))

    private lateinit var db: AppDatabase
    private lateinit var viewModel: TrashViewModel

    @Before
    fun setUp() {
        db = TestDatabaseFactory.create()
        // 用 StandardTestDispatcher：Unconfined 会让 ViewModel 里 combine 的各路上游
        // 「边订阅边发射」，先订阅到的 StateFlow 立刻给出旧值，后面几个还没订阅完，
        // 结果永远拼不出完整快照。Standard 下所有发射都排在同一个队列里，
        // 由 advanceUntilIdle() 统一推进，时序可预期。
        Dispatchers.setMain(StandardTestDispatcher())
        // 每个测试方法各自造一份 mock：JUnit4 会为每个 @Test 新建测试类实例，
        // 类字段上的 mockk 会再次注册静态 observer，把旧实例的重复收集留在 store 里。
        val settings: SettingsDataStore = mockk(relaxed = true)
        coEvery { settings.settings } returns settingsFlow
        coEvery { settings.current() } returns AppSettings(recycleBinDays = 30)
        viewModel =
            TrashViewModel(
                txnRepository = txnRepo,
                accountRepository = accountRepo,
                budgetRepository = budgetRepo,
                settings = settings,
                cleanup = TrashCleanupUseCase(db, settings),
            )
    }

    @After
    fun tearDown() {
        if (this::db.isInitialized) db.close()
        Dispatchers.resetMain()
    }

    private fun txn(
        id: String,
        deletedAt: Long? = null,
        note: String = "",
    ) = Txn(
        id = id,
        type = TxnType.EXPENSE,
        amount = 12_000,
        categoryId = "c1",
        accountId = "a1",
        dateTime = 1_700_000_000_000,
        dateKey = 20261113,
        note = note,
        deletedAt = deletedAt,
        createdAt = 1_700_000_000_000,
        updatedAt = 1_700_000_000_000,
    )

    @Test
    fun tabs() =
        runTest {
            txnRepo.put(txn("t1", deletedAt = 1_700_000_100_000, note = "打车"))
            txnRepo.items["t2"] = txn("t2") // 没删，不该出现
            accountRepo.upsert(Account(id = "a1", name = "现金", type = AccountType.SAVINGS))
            accountRepo.markDeleted("a1", 1_700_000_200_000)
            budgetRepo.put(
                Budget(
                    id = "b1",
                    name = "餐饮预算",
                    amount = 100_000,
                    monthKey = 202611,
                    scope = BudgetScope.TOTAL,
                    deletedAt = 1_700_000_300_000,
                ),
            )

            val s = waitVmState { it.total == 3 }
            assertEquals(listOf("t1"), s.txns.map { it.id })
            assertEquals(listOf("a1"), s.accounts.map { it.id })
            assertEquals(listOf("b1"), s.budgets.map { it.id })
            assertEquals(3, s.total)
            assertEquals("打车", s.txns.first().title)

            viewModel.selectTab(TrashTab.ACCOUNT)
            assertEquals(listOf("a1"), viewModel.state.value.current.map { it.id })
            viewModel.selectTab(TrashTab.BUDGET)
            assertEquals(202611, viewModel.state.value.current.first().monthKey)
        }

    @Test
    fun restoreTxn() =
        runTest {
            txnRepo.put(txn("t1", deletedAt = 1_700_000_100_000))
            waitVmState { it.txns.size == 1 }
            viewModel.restoreTxn("t1")
            advanceUntilIdle()
            assertNull(txnRepo.items["t1"]?.deletedAt)
            assertNull(waitVmState { it.txns.isEmpty() }.txns.firstOrNull())
            assertEquals("已还原账目", viewModel.state.value.message)
        }

    @Test
    fun purgeTxn() =
        runTest {
            txnRepo.put(txn("t1", deletedAt = 1_700_000_100_000))
            viewModel.purgeTxn("t1")
            advanceUntilIdle()
            assertNull(txnRepo.items["t1"])
            assertEquals("已彻底删除", viewModel.state.value.message)
        }

    @Test
    fun restoreAccount() =
        runTest {
            // 还原账户会把同一次删除的流水一起捞回来，所以回收站里先放一条
            txnRepo.put(txn("t1", deletedAt = 1_700_000_200_000))
            accountRepo.upsert(Account(id = "a1", name = "现金", type = AccountType.SAVINGS))
            accountRepo.markDeleted("a1", 1_700_000_200_000)
            waitVmState { it.accounts.isNotEmpty() }

            viewModel.restoreAccount("a1")
            advanceUntilIdle()
            waitVmState { it.txns.isEmpty() && it.accounts.isEmpty() }

            assertTrue("a1" !in accountRepo.deletedAt)
            assertTrue(viewModel.state.value.accounts.isEmpty())
        }

    @Test
    fun purgeBudget() =
        runTest {
            budgetRepo.put(
                Budget(id = "b1", name = "x", amount = 1, monthKey = 202611, deletedAt = 1_700_000_300_000),
            )
            viewModel.purgeBudget("b1", 202611)
            advanceUntilIdle()
            assertTrue(budgetRepo.rows.isEmpty())
        }

    /**
     * 等到 state 满足 [predicate]。
     *
     * ViewModel 在 `viewModelScope.launch(Dispatchers.Default)` 里汇总三个仓库 + 设置，
     * 汇总这一步真实跑在别的线程上，所以不能假设「调用完动作，反射读 state 立刻就是新值」，
     * 只能轮询到这个条件成立（最多约 2 秒，正常几毫秒内就满足）。
     *
     * [advanceUntilIdle] 只推进本测试调度器上的任务，管不到真实线程；这里每轮交替
     * 「推进本调度器」和「短暂让出 CPU」，两条路都能往前走。
     */
    private fun TestScope.waitVmState(predicate: (TrashState) -> Boolean): TrashState {
        repeat(200) {
            viewModel.state.value.let { if (predicate(it)) return it }
            advanceUntilIdle()
            Thread.sleep(10)
        }
        return viewModel.state.value
    }

    @Test
    fun clearCurrentOnly() =
        runTest {
            viewModel.selectTab(TrashTab.TXN)
            txnRepo.put(txn("t1", deletedAt = 1_700_000_100_000))
            accountRepo.upsert(Account(id = "a1", name = "现金", type = AccountType.SAVINGS))
            accountRepo.markDeleted("a1", 1_700_000_200_000)
            waitVmState { it.txns.size == 1 }

            viewModel.clearCurrent()
            advanceUntilIdle()
            waitVmState { it.txns.isEmpty() }

            // 清空「账目」页只会硬删账目，账户页的内容必须原样留着
            assertTrue(txnRepo.items.isEmpty())
            assertTrue("a1" in accountRepo.deletedAt)
            assertEquals("已清空 1 条", viewModel.state.value.message)
        }

    @Test
    fun retentionLabel() =
        runTest {
            assertEquals("删除后保留 30 天，到期自动清理", viewModel.state.value.retentionLabel)
            settingsFlow.value = AppSettings(recycleBinDays = 0)
            assertEquals("永久保留，不会自动清理", waitVmState { it.retentionDays == 0 }.retentionLabel)
        }
}
