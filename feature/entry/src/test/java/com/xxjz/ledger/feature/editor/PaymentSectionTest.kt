package com.xxjz.ledger.feature.editor

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.xxjz.ledger.core.designsystem.theme.XxjzTheme
import com.xxjz.ledger.domain.model.Account
import com.xxjz.ledger.domain.model.AccountType
import com.xxjz.ledger.domain.model.Category
import com.xxjz.ledger.domain.model.TxnType
import com.xxjz.ledger.domain.usecase.RecordDebtOpUseCase
import com.xxjz.ledger.domain.usecase.RefundTxnUseCase
import com.xxjz.ledger.domain.usecase.SaveDebtAccountUseCase
import com.xxjz.ledger.domain.usecase.SaveTxnUseCase
import com.xxjz.ledger.testutil.FakeAccountRepository
import com.xxjz.ledger.testutil.FakeCategoryRepository
import com.xxjz.ledger.testutil.FakeInstallmentRepository
import com.xxjz.ledger.testutil.FakeMerchantRepository
import com.xxjz.ledger.testutil.FakeSettingsDataStore
import com.xxjz.ledger.testutil.FakeTagRepository
import com.xxjz.ledger.testutil.FakeTemplateRepository
import com.xxjz.ledger.testutil.FakeTxnRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 付款方式控件（v1.9.5 第 1 条）。
 *
 * 上一版是一个 20dp 的 ⇄ 图标：连点两下是「开 → 关」，看起来完全像没反应。
 * 这里钉死三件事：两段都在、点得动、重复点同一段不会把状态翻回去。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w360dp-h640dp-xhdpi")
class PaymentSectionTest {
    @get:Rule
    val compose = createComposeRule()

    private val dispatcher = StandardTestDispatcher()
    private val txnRepo = FakeTxnRepository()
    private val categoryRepo = FakeCategoryRepository()
    private val accountRepo = FakeAccountRepository()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        categoryRepo.categories = listOf(Category(id = "food", name = "餐饮", type = TxnType.EXPENSE))
        accountRepo.items["acc"] = Account(id = "acc", name = "现金", type = AccountType.CASH)
        accountRepo.items["acc2"] = Account(id = "acc2", name = "招行", type = AccountType.SAVINGS)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(): EditorViewModel =
        EditorViewModel(
            txnRepository = txnRepo,
            categoryRepository = categoryRepo,
            accountRepository = accountRepo,
            templateRepository = FakeTemplateRepository(),
            saveTxn = SaveTxnUseCase(txnRepo, categoryRepo),
            settings = FakeSettingsDataStore(),
            recordDebtOp = RecordDebtOpUseCase(accountRepo, txnRepo),
            saveDebtAccount = SaveDebtAccountUseCase(accountRepo, RecordDebtOpUseCase(accountRepo, txnRepo)),
            tagRepository = FakeTagRepository(),
            merchantRepository = FakeMerchantRepository(),
            installmentRepository = FakeInstallmentRepository(),
            refundTxn = RefundTxnUseCase(txnRepo, categoryRepo),
        )

    private fun render(vm: EditorViewModel) {
        compose.setContent {
            XxjzTheme {
                val state by vm.state.collectAsState()
                PaymentSection(
                    state = state,
                    viewModel = vm,
                    onPickAccount = {},
                    onPickToAccount = {},
                    onPickDebtAccount = {},
                    onNewDebt = {},
                )
            }
        }
        dispatcher.scheduler.advanceUntilIdle()
        compose.waitForIdle()
    }

    @Test
    fun `支出显示付款方式两段`() {
        val vm = viewModel()
        render(vm)

        compose.onNodeWithTag("split_selector").assertExists()
        compose.onNodeWithTag("split_mode_single").assertExists()
        compose.onNodeWithTag("split_mode_split").assertExists()
    }

    @Test
    fun `点组合支付展开分摊面板`() {
        val vm = viewModel()
        render(vm)

        compose.onNodeWithTag("split_mode_split").performClick()
        compose.waitForIdle()

        assertTrue(vm.state.value.splitMode)
        compose.onNodeWithTag("split_total").assertExists()
    }

    @Test
    fun `连点组合支付四次状态不翻转`() {
        val vm = viewModel()
        render(vm)

        repeat(4) {
            compose.onNodeWithTag("split_mode_split").performClick()
            compose.waitForIdle()
        }

        assertTrue("点的是同一段，不该翻回去", vm.state.value.splitMode)
        compose.onNodeWithTag("split_total").assertExists()
    }

    @Test
    fun `连点单账户四次也不翻转`() {
        val vm = viewModel()
        render(vm)
        compose.onNodeWithTag("split_mode_split").performClick()
        compose.waitForIdle()

        repeat(4) {
            compose.onNodeWithTag("split_mode_single").performClick()
            compose.waitForIdle()
        }

        assertFalse(vm.state.value.splitMode)
    }

    @Test
    fun `转账不显示付款方式`() {
        val vm = viewModel()
        vm.setType(TxnType.TRANSFER)
        render(vm)

        compose.onNodeWithTag("split_selector").assertDoesNotExist()
    }

    @Test
    fun `支出显示付款账户`() {
        val vm = viewModel()
        render(vm)

        compose.onNodeWithText("付款账户").assertExists()
    }

    @Test
    fun `收入显示收款账户而不是付款账户`() {
        val vm = viewModel()
        vm.setType(TxnType.INCOME)
        render(vm)

        compose.onNodeWithText("收款账户").assertExists()
        compose.onAllNodesWithText("付款账户").assertCountEquals(0)
    }

    @Test
    fun `转账显示转出账户`() {
        val vm = viewModel()
        vm.setType(TxnType.TRANSFER)
        render(vm)

        compose.onNodeWithText("转出账户").assertExists()
        compose.onNodeWithText("转入账户").assertExists()
    }

    @Test
    fun `组合面板三个账户来回点都能切焦点`() {
        accountRepo.items["acc3"] = Account(id = "acc3", name = "建行", type = AccountType.SAVINGS)
        val vm = viewModel()
        render(vm)
        compose.onNodeWithTag("split_mode_split").performClick()
        compose.waitForIdle()

        listOf("acc", "acc2", "acc3", "acc", "acc2").forEach { id ->
            compose.onNodeWithTag("split_input_$id").performTouchInput { click(Offset(centerX, 2f)) }
            compose.onNodeWithTag("split_input_$id").assertIsFocused()
        }
    }

    @Test
    fun `账户标签与占位按类型给的规则表`() {
        assertEquals("付款账户", accountLabelOf(TxnType.EXPENSE))
        assertEquals("收款账户", accountLabelOf(TxnType.INCOME))
        assertEquals("转出账户", accountLabelOf(TxnType.TRANSFER))
        assertEquals("资金账户", accountLabelOf(TxnType.DEBT))

        assertEquals("请选择", accountPlaceholderOf(TxnType.EXPENSE))
        assertEquals("请选择收款账户", accountPlaceholderOf(TxnType.INCOME))
        assertEquals("选择转出账户", accountPlaceholderOf(TxnType.TRANSFER))
        assertEquals("选择资金账户", accountPlaceholderOf(TxnType.DEBT))
    }
}
