package com.xxjz.ledger.feature.ledger

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import com.xxjz.ledger.core.designsystem.theme.XxjzTheme
import com.xxjz.ledger.domain.model.Category
import com.xxjz.ledger.domain.model.Txn
import com.xxjz.ledger.domain.model.TxnType
import com.xxjz.ledger.domain.usecase.GetBudgetForMonthUseCase
import com.xxjz.ledger.domain.usecase.StartInstallmentUseCase
import com.xxjz.ledger.testutil.FakeAccountRepository
import com.xxjz.ledger.testutil.FakeBudgetRepository
import com.xxjz.ledger.testutil.FakeCategoryRepository
import com.xxjz.ledger.testutil.FakeInstallmentRepository
import com.xxjz.ledger.testutil.FakeSettingsDataStore
import com.xxjz.ledger.testutil.FakeTemplateRepository
import com.xxjz.ledger.testutil.FakeTxnRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * 首页列表布局（v1.9.5 第 4 条）。
 *
 * 这里只钉一件事：**列表拿到的是"剩下的高度"，不是"整屏高度"**。
 *
 * 上一版把 `LazyColumn` 写成 `fillMaxSize()`，在 `Column` 里它会请求父级最大高度，
 * 于是列表比可用空间高出一截，可视区被挤到四五条，滚到底也够不到预算卡。
 * 用 360×640dp 的小屏做基准，两种写法的差值最明显。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w360dp-h640dp-xhdpi")
class LedgerListLayoutTest {
    @get:Rule
    val compose = createComposeRule()

    private val dispatcher = StandardTestDispatcher()
    private val txnRepo = FakeTxnRepository()
    private val categoryRepo = FakeCategoryRepository()
    private val accountRepo = FakeAccountRepository()
    private val settings = FakeSettingsDataStore()

    private val today: LocalDate = LocalDate.of(2026, 11, 16)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        categoryRepo.categories = listOf(Category(id = "food", name = "餐饮", type = TxnType.EXPENSE))
        accountRepo.items["acc"] =
            com.xxjz.ledger.domain.model.Account(
                id = "acc",
                name = "招行储蓄卡",
                type = com.xxjz.ledger.domain.model.AccountType.SAVINGS,
            )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun seed(count: Int) {
        val base = today.atTime(12, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        repeat(count) { index ->
            val millis = base - index * 60_000L
            txnRepo.items["t$index"] =
                Txn(
                    id = "t$index",
                    type = TxnType.EXPENSE,
                    amount = 1000L + index,
                    categoryId = "food",
                    accountId = "acc",
                    dateTime = millis,
                    dateKey = today.year * 10000 + today.monthValue * 100 + today.dayOfMonth,
                    note = "午饭$index",
                    createdAt = millis,
                    updatedAt = millis,
                )
        }
    }

    private fun viewModel(): LedgerViewModel =
        LedgerViewModel(
            txnRepository = txnRepo,
            categoryRepository = categoryRepo,
            templateRepository = FakeTemplateRepository(),
            budgetForMonth = GetBudgetForMonthUseCase(FakeBudgetRepository()),
            accountRepository = accountRepo,
            installmentRepository = FakeInstallmentRepository(),
            startInstallmentUseCase = StartInstallmentUseCase(txnRepo, accountRepo, FakeInstallmentRepository()),
            settingsRepository = settings,
        )

    @Test
    fun `列表高度是剩余空间而不是整屏`() {
        seed(30)
        val vm = viewModel()
        compose.setContent {
            XxjzTheme { LedgerScreen(nav = LedgerNav(), viewModel = vm) }
        }
        // StateFlow 跑在 TestDispatcher 上，waitForIdle 不会替它推进虚拟时间
        dispatcher.scheduler.advanceUntilIdle()
        compose.waitForIdle()

        val listHeight = compose.onNodeWithTag("txn_list").getBoundsInRoot().height
        // 640dp 屏减去月头 + 汇总卡 + 模板条后约 330dp；若退回 fillMaxSize 会等于整屏高度
        assertTrue("列表高度应小于整屏（当前 $listHeight）", listHeight < 600.dp)
        assertTrue("列表不该被压没（当前 $listHeight）", listHeight > 200.dp)
    }

    @Test
    fun `滚到底能看到预算卡`() {
        seed(30)
        val vm = viewModel()
        compose.setContent {
            XxjzTheme { LedgerScreen(nav = LedgerNav(), viewModel = vm) }
        }
        // StateFlow 跑在 TestDispatcher 上，waitForIdle 不会替它推进虚拟时间
        dispatcher.scheduler.advanceUntilIdle()
        compose.waitForIdle()

        // 只有列表拿到正确高度时，最后一个 item 才滚得到；修复前它永远在屏幕外
        compose.onNodeWithTag("txn_list").performScrollToNode(hasTestTag("budget_card"))
        compose.onNodeWithTag("budget_card").assertExists()
    }

    @Test
    fun `流水行显示时间与账户`() {
        seed(1)
        val vm = viewModel()
        compose.setContent {
            XxjzTheme {
                androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
                    LedgerScreen(nav = LedgerNav(), viewModel = vm)
                }
            }
        }
        // StateFlow 跑在 TestDispatcher 上，waitForIdle 不会替它推进虚拟时间
        dispatcher.scheduler.advanceUntilIdle()
        compose.waitForIdle()

        compose.onNodeWithText("12:00", substring = true).assertExists()
        compose.onNodeWithText("招行储蓄卡", substring = true).assertExists()
    }

    // ---------- 四格汇总（v1.9.5 第 5 条） ----------

    @Test
    fun `汇总卡四格齐全`() {
        seed(2)
        val vm = viewModel()
        compose.setContent {
            XxjzTheme { LedgerScreen(nav = LedgerNav(), viewModel = vm) }
        }
        dispatcher.scheduler.advanceUntilIdle()
        compose.waitForIdle()

        compose.onNodeWithTag("summary_income").assertExists()
        compose.onNodeWithTag("summary_expense").assertExists()
        compose.onNodeWithTag("summary_balance").assertExists()
        compose.onNodeWithTag("summary_pending").assertExists()
    }

    @Test
    fun `待报销格显示笔数，点击进待报销页`() {
        seed(1)
        txnRepo.items.getValue("t0").let { t ->
            txnRepo.items["t0"] = t.copy(pendingReimbursement = true)
        }
        var opened = false
        val vm = viewModel()
        compose.setContent {
            XxjzTheme { LedgerScreen(nav = LedgerNav(onOpenReimbursement = { opened = true }), viewModel = vm) }
        }
        dispatcher.scheduler.advanceUntilIdle()
        compose.waitForIdle()

        // clickable 会合并子节点语义，笔数那个小字得去未合并树里找
        compose.onNodeWithTag("summary_pending_count", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("summary_pending").performClick()

        assertTrue("点击待报销格要能进待报销页", opened)
    }

    @Test
    fun `没有待报销时显示暂无`() {
        seed(1)
        val vm = viewModel()
        compose.setContent {
            XxjzTheme { LedgerScreen(nav = LedgerNav(), viewModel = vm) }
        }
        dispatcher.scheduler.advanceUntilIdle()
        compose.waitForIdle()

        compose.onNodeWithText("暂无", substring = true).assertExists()
    }

    @Test
    fun `从没备份过时首页提示去导出`() {
        seed(2)
        val vm = viewModel()
        compose.setContent {
            XxjzTheme { LedgerScreen(nav = LedgerNav(), viewModel = vm) }
        }
        dispatcher.scheduler.advanceUntilIdle()
        compose.waitForIdle()

        // v1.9.6 第 1 条：提示条回来了，但带 × 可关；从没备份过时措辞是「去导出」
        compose.onNodeWithTag("home_backup_hint").assertExists()
        compose.onNodeWithText("还没有备份过，建议导出一份").assertExists()
    }

    @Test
    fun `超过七天没备份时首页出现可关闭的提示条`() {
        seed(3)
        settings.replaceAll(
            com.xxjz.ledger.data.datastore.AppSettings(
                lastBackupAt = System.currentTimeMillis() - 12L * 24 * 60 * 60 * 1000,
            ),
        )
        val vm = viewModel()
        compose.setContent { XxjzTheme { LedgerScreen(nav = LedgerNav(), viewModel = vm) } }
        dispatcher.scheduler.advanceUntilIdle()
        compose.waitForIdle()

        compose.onNodeWithTag("home_backup_hint").assertExists()
        compose.onNodeWithText("距上次备份已超过 7 天").assertExists()

        compose.onNodeWithTag("home_backup_close").performClick()
        dispatcher.scheduler.advanceUntilIdle()
        compose.waitForIdle()

        compose.onAllNodesWithTag("home_backup_hint").assertCountEquals(0)
    }

    // ---------- 最近四笔 + 更多明细（v1.9.6 第 2 条） ----------

    /** 每天记一笔，用来验证「跨天也连着排」。 */
    private fun seedDays(count: Int) {
        repeat(count) { index ->
            val date = today.minusDays(index.toLong())
            val millis = date.atTime(12, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            txnRepo.items["d$index"] =
                Txn(
                    id = "d$index",
                    type = TxnType.EXPENSE,
                    amount = 1000L + index,
                    categoryId = "food",
                    accountId = "acc",
                    dateTime = millis,
                    dateKey = date.year * 10000 + date.monthValue * 100 + date.dayOfMonth,
                    note = "跨天$index",
                    createdAt = millis,
                    updatedAt = millis,
                )
        }
    }

    @Test
    fun `三十笔时首页只显示最近四笔`() {
        seed(30)
        val vm = viewModel()
        compose.setContent { XxjzTheme { LedgerScreen(nav = LedgerNav(), viewModel = vm) } }
        dispatcher.scheduler.advanceUntilIdle()
        compose.waitForIdle()

        // 打开就在顶部：最新一笔必须第一眼可见，不需要用户先往上滚
        compose.onNodeWithTag("txn_item_t0").assertExists()
        compose.onNodeWithTag("txn_item_t3").assertExists()
        compose.onAllNodesWithTag("txn_item_t4").assertCountEquals(0)
    }

    @Test
    fun `四笔以内不出更多明细卡`() {
        seed(3)
        val vm = viewModel()
        compose.setContent { XxjzTheme { LedgerScreen(nav = LedgerNav(), viewModel = vm) } }
        dispatcher.scheduler.advanceUntilIdle()
        compose.waitForIdle()

        compose.onAllNodesWithTag("more_txns").assertCountEquals(0)
        compose.onNodeWithTag("txn_item_t2").assertExists()
    }

    @Test
    fun `超过四笔时出更多明细卡并写总笔数`() {
        seed(12)
        val vm = viewModel()
        compose.setContent { XxjzTheme { LedgerScreen(nav = LedgerNav(), viewModel = vm) } }
        dispatcher.scheduler.advanceUntilIdle()
        compose.waitForIdle()

        compose.onNodeWithTag("more_txns").assertExists()
        compose.onNodeWithText("共 12 笔").assertExists()
    }

    @Test
    fun `最近四笔跨天也连着排且不插日期头`() {
        seedDays(3)
        val vm = viewModel()
        compose.setContent { XxjzTheme { LedgerScreen(nav = LedgerNav(), viewModel = vm) } }
        dispatcher.scheduler.advanceUntilIdle()
        compose.waitForIdle()

        // 三条来自三天，但首页不分组：不该出现「今天 / 昨天」这类日期头
        compose.onAllNodesWithText("今天").assertCountEquals(0)
        // 每笔自带日期
        compose.onNodeWithText("11-16 12:00", substring = true).assertExists()
        compose.onNodeWithText("11-15 12:00", substring = true).assertExists()
    }

    @Test
    fun `更多明细卡在预算卡上面`() {
        seed(12)
        val vm = viewModel()
        compose.setContent { XxjzTheme { LedgerScreen(nav = LedgerNav(), viewModel = vm) } }
        dispatcher.scheduler.advanceUntilIdle()
        compose.waitForIdle()

        val more = compose.onNodeWithTag("more_txns").getBoundsInRoot()
        val budget = compose.onNodeWithTag("budget_card").getBoundsInRoot()
        assertTrue("更多明细卡应在预算卡上方", more.top < budget.top)
    }
}
