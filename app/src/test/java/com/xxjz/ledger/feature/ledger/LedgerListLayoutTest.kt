package com.xxjz.ledger.feature.ledger

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import com.xxjz.ledger.core.designsystem.theme.XxjzTheme
import com.xxjz.ledger.domain.model.Category
import com.xxjz.ledger.domain.model.Txn
import com.xxjz.ledger.domain.model.TxnType
import com.xxjz.ledger.domain.usecase.GetBudgetForMonthUseCase
import com.xxjz.ledger.testutil.FakeAccountRepository
import com.xxjz.ledger.testutil.FakeBudgetRepository
import com.xxjz.ledger.testutil.FakeCategoryRepository
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
            settings = FakeSettingsDataStore(),
            budgetForMonth = GetBudgetForMonthUseCase(FakeBudgetRepository()),
            accountRepository = accountRepo,
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
}
