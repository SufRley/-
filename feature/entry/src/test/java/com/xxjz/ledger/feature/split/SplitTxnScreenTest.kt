package com.xxjz.ledger.feature.split

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.SavedStateHandle
import com.xxjz.ledger.core.common.DateUtils
import com.xxjz.ledger.core.designsystem.theme.XxjzTheme
import com.xxjz.ledger.domain.model.Account
import com.xxjz.ledger.domain.model.AccountType
import com.xxjz.ledger.domain.model.Txn
import com.xxjz.ledger.domain.model.TxnType
import com.xxjz.ledger.domain.usecase.SplitTxnUseCase
import com.xxjz.ledger.navigation.Routes
import com.xxjz.ledger.testutil.FakeAccountRepository
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
import java.time.LocalDateTime

/**
 * 拆分明细页（v1.9.5 第 8 条）。
 *
 * 页面层的核心只有一条：**合计对不上时保存按钮必须是灰的**。
 * 这条挡住的是「拆完总额变了」这种最难事后发现的错账。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w360dp-h640dp-xhdpi")
class SplitTxnScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val dispatcher = StandardTestDispatcher()
    private val txnRepo = FakeTxnRepository()
    private val accountRepo = FakeAccountRepository()
    private val now = DateUtils.toMillis(LocalDateTime.of(2026, 11, 16, 12, 0))

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        accountRepo.items["a"] = Account(id = "a", name = "招行", type = AccountType.SAVINGS)
        accountRepo.items["b"] = Account(id = "b", name = "现金", type = AccountType.CASH)
        txnRepo.items["t1"] =
            Txn(
                id = "t1",
                type = TxnType.EXPENSE,
                amount = 30_000,
                categoryId = "food",
                accountId = "a",
                dateTime = now,
                dateKey = DateUtils.dateKeyOf(now),
                note = "午饭",
                createdAt = now,
                updatedAt = now,
            )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(): SplitTxnViewModel =
        SplitTxnViewModel(
            txnRepository = txnRepo,
            accountRepository = accountRepo,
            splitTxn = SplitTxnUseCase(txnRepo, accountRepo),
            savedStateHandle = SavedStateHandle(mapOf(Routes.ARG_TXN_ID to "t1")),
        )

    private fun render(vm: SplitTxnViewModel) {
        compose.setContent {
            XxjzTheme { SplitTxnScreen(onBack = {}, onDone = {}, viewModel = vm) }
        }
        dispatcher.scheduler.advanceUntilIdle()
        compose.waitForIdle()
    }

    @Test
    fun `打开就是两行明细`() {
        render(viewModel())

        compose.onNodeWithTag("split_row_0").assertExists()
        compose.onNodeWithTag("split_row_1").assertExists()
        compose.onNodeWithTag("split_total").assertExists()
    }

    @Test
    fun `只有一行有金额时保存置灰`() {
        render(viewModel())

        // 第一行预填全额、第二行空 → 合计等于原额，但第二行金额为 0，仍不该放行
        compose.onNodeWithTag("split_save").assertIsNotEnabled()
    }

    @Test
    fun `合计对上后保存可点`() {
        val vm = viewModel()
        render(vm)

        vm.setAmount(vm.state.value.rows[0].key, "200")
        val key = vm.state.value.rows[1].key
        vm.setAccount(key, "b")
        vm.setAmount(key, "100")
        compose.waitForIdle()

        compose.onNodeWithTag("split_save").assertIsEnabled()
    }

    @Test
    fun `改金额后差额提示跟着变`() {
        val vm = viewModel()
        render(vm)

        compose.onNodeWithTag("split_amount_1").performTextReplacement("100")
        compose.waitForIdle()

        compose.onNodeWithTag("split_diff").assertExists()
    }

    @Test
    fun `改过明细后按返回弹放弃确认`() {
        val vm = viewModel()
        render(vm)

        vm.setAmount(vm.state.value.rows[1].key, "100")
        compose.waitForIdle()

        compose.onNodeWithTag("split_back").performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("confirm_dialog").assertExists()
    }

    @Test
    fun `没动过直接返回不弹确认`() {
        var backed = false
        val vm = viewModel()
        compose.setContent {
            XxjzTheme { SplitTxnScreen(onBack = { backed = true }, onDone = {}, viewModel = vm) }
        }
        dispatcher.scheduler.advanceUntilIdle()
        compose.waitForIdle()

        compose.onNodeWithTag("split_back").performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("confirm_dialog").assertDoesNotExist()
        assertTrue(backed)
    }

    @Test
    fun `两个金额框来回点四次，每次都能切过去`() {
        val vm = viewModel()
        render(vm)

        // AC-3.1：上一版 52dp 强压时，点多了之后某个框会彻底点不动
        repeat(2) {
            compose.onNodeWithTag("split_amount_0").performTouchInput { click(Offset(centerX, 2f)) }
            compose.onNodeWithTag("split_amount_0").assertIsFocused()

            compose.onNodeWithTag("split_amount_1").performTouchInput { click(Offset(centerX, 2f)) }
            compose.onNodeWithTag("split_amount_1").assertIsFocused()
        }
    }

    @Test
    fun `填第二行后原账户自动让出余额`() {
        val vm = viewModel()
        render(vm)

        vm.setAccount(vm.state.value.rows[1].key, "b")
        compose.waitForIdle()
        compose.onNodeWithTag("split_amount_1").performTextReplacement("100")
        compose.waitForIdle()

        assertTrue(
            "原账户应自动变 200，实际 ${vm.state.value.rows[0].amountText}",
            vm.state.value.rows[0].amountText == "200",
        )
    }
}
