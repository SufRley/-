package com.xxjz.ledger.feature.account

import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import com.xxjz.ledger.core.designsystem.theme.XxjzTheme
import com.xxjz.ledger.domain.model.Account
import com.xxjz.ledger.domain.model.AccountType
import com.xxjz.ledger.domain.usecase.DeleteAccountUseCase
import com.xxjz.ledger.domain.usecase.GetAssetSummaryUseCase
import com.xxjz.ledger.testutil.FakeAccountRepository
import com.xxjz.ledger.testutil.FakeSettingsDataStore
import com.xxjz.ledger.testutil.FakeTxnRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 账户页的上下顺序（v1.9.5 第 6 条）。
 *
 * 净资产是这一页的主信息，待报销只是个二级入口。用纵坐标断言顺序，
 * 比截图比对稳定，也不会因为机型高度不同而失效。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w360dp-h640dp-xhdpi")
class AccountPageOrderTest {
    @get:Rule
    val compose = createComposeRule()

    private val dispatcher = StandardTestDispatcher()
    private val accountRepo = FakeAccountRepository()
    private val txnRepo = FakeTxnRepository()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        accountRepo.items["cash"] =
            Account(id = "cash", name = "现金", type = AccountType.CASH, initialBalance = 100_00)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(): AccountListViewModel =
        AccountListViewModel(
            repository = accountRepo,
            txnRepository = txnRepo,
            deleteAccount = DeleteAccountUseCase(accountRepo),
            summaryUseCase = GetAssetSummaryUseCase(accountRepo),
            settings = FakeSettingsDataStore(),
        )

    @Test
    fun `净资产卡在待报销入口上方`() {
        val vm = viewModel()
        compose.setContent {
            XxjzTheme { AccountListScreen(onBack = {}, onOpen = {}, viewModel = vm) }
        }
        dispatcher.scheduler.advanceUntilIdle()
        compose.waitForIdle()

        val netTop = compose.onNodeWithTag("net_assets").getBoundsInRoot().top
        val reimburseTop = compose.onNodeWithTag("entry_reimbursement").getBoundsInRoot().top

        assertTrue("净资产应在上（net=$netTop, reimburse=$reimburseTop）", netTop < reimburseTop)
    }

    @Test
    fun `待报销入口只剩一处，不再有并排小卡`() {
        val vm = viewModel()
        compose.setContent {
            XxjzTheme { AccountListScreen(onBack = {}, onOpen = {}, viewModel = vm) }
        }
        dispatcher.scheduler.advanceUntilIdle()
        compose.waitForIdle()

        // 对调位置后如果还留着旧的半宽小卡，这里会是 2 个节点
        assertEquals(1, compose.onAllNodesWithTag("entry_reimbursement").fetchSemanticsNodes().size)
    }
}
