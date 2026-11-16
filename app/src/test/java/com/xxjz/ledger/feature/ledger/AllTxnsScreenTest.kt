package com.xxjz.ledger.feature.ledger

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.xxjz.ledger.core.designsystem.theme.XxjzTheme
import com.xxjz.ledger.domain.model.Account
import com.xxjz.ledger.domain.model.AccountType
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
 * 全部明细页（v1.9.6 第 2 条）。
 *
 * 与首页正好相反：这里**要**按天分组、**要**日期头，行内只写时刻。
 * 两处口径不同是故意的——首页当概览看，这里当台账查。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w360dp-h640dp-xhdpi")
class AllTxnsScreenTest {
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
        accountRepo.items["acc"] = Account(id = "acc", name = "招行储蓄卡", type = AccountType.SAVINGS)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun seedAt(
        id: String,
        date: LocalDate,
    ) {
        val millis = date.atTime(12, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        txnRepo.items[id] =
            Txn(
                id = id,
                type = TxnType.EXPENSE,
                amount = 1000,
                categoryId = "food",
                accountId = "acc",
                dateTime = millis,
                dateKey = date.year * 10000 + date.monthValue * 100 + date.dayOfMonth,
                note = "午饭",
                createdAt = millis,
                updatedAt = millis,
            )
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

    private fun setContent(
        monthKey: Int = 0,
        onBack: () -> Unit = {},
        onOpenTxn: (String) -> Unit = {},
    ) {
        val vm = viewModel()
        compose.setContent {
            XxjzTheme {
                AllTxnsScreen(
                    initialMonthKey = monthKey,
                    onBack = onBack,
                    onOpenTxn = onOpenTxn,
                    onEditTxn = {},
                    onOpenAccount = {},
                    onOpenRecurring = {},
                    onOpenSplit = {},
                    onOpenSearch = {},
                    onOpenCalendar = {},
                    viewModel = vm,
                )
            }
        }
        dispatcher.scheduler.advanceUntilIdle()
        compose.waitForIdle()
    }

    @Test
    fun `按天分组带日期头`() {
        seedAt("a", today)
        seedAt("b", today.minusDays(1))
        setContent()

        compose.onNodeWithTag("all_txns_list").assertExists()
        compose.onAllNodesWithText("今天").assertCountEquals(1)
        compose.onAllNodesWithText("昨天").assertCountEquals(1)
    }

    @Test
    fun `行内只写时刻，日期交给组头`() {
        seedAt("a", today)
        setContent()

        compose.onNodeWithText("12:00", substring = true).assertExists()
        // 有组头就不该在行里再写一遍「11-16 12:00」
        compose.onAllNodesWithText("11-16 12:00", substring = true).assertCountEquals(0)
    }

    @Test
    fun `没有流水时显示空态`() {
        setContent()

        compose.onNodeWithText("这个月还没有账目").assertExists()
    }

    @Test
    fun `点返回回到首页`() {
        var back = false
        setContent(onBack = { back = true })

        compose.onNodeWithTag("all_txns_back").performClick()

        assertTrue("点返回要能退出本页", back)
    }

    @Test
    fun `点一条流水进详情`() {
        seedAt("a", today)
        var opened: String? = null
        setContent(onOpenTxn = { opened = it })

        compose.onNodeWithTag("txn_item_a").performClick()

        assertTrue("点行应打开 $opened", opened == "a")
    }

    @Test
    fun `换月按钮切换月份`() {
        seedAt("a", today)
        setContent(monthKey = 202611)

        compose.onNodeWithTag("all_txns_month").assertExists()
        compose.onNodeWithTag("all_txns_prev").performClick()
        dispatcher.scheduler.advanceUntilIdle()
        compose.waitForIdle()

        compose.onNodeWithText("2026年10月").assertExists()
    }
}
