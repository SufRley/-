package com.xxjz.ledger.feature.editor

import app.cash.turbine.test
import com.xxjz.ledger.data.datastore.AppSettings
import com.xxjz.ledger.data.datastore.SettingsDataStore
import com.xxjz.ledger.domain.model.Account
import com.xxjz.ledger.domain.model.AccountType
import com.xxjz.ledger.domain.model.Category
import com.xxjz.ledger.domain.model.Template
import com.xxjz.ledger.domain.model.TxnType
import com.xxjz.ledger.domain.usecase.RecordDebtOpUseCase
import com.xxjz.ledger.domain.usecase.SaveDebtAccountUseCase
import com.xxjz.ledger.domain.usecase.SaveTxnUseCase
import com.xxjz.ledger.testutil.FakeAccountRepository
import com.xxjz.ledger.testutil.FakeCategoryRepository
import com.xxjz.ledger.testutil.FakeTemplateRepository
import com.xxjz.ledger.testutil.FakeTxnRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** M2 记账面板 ViewModel：输入 → 求值 → 保存 → 事件（AC-01 ~ AC-09）。 */
@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("记账面板状态机")
class EditorViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val txnRepo = FakeTxnRepository()
    private val categoryRepo = FakeCategoryRepository()
    private val accountRepo = FakeAccountRepository()
    private val templateRepo = FakeTemplateRepository()
    private val settings = mockk<SettingsDataStore>(relaxed = true)

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        categoryRepo.categories =
            listOf(
                Category(id = "food", name = "餐饮", type = TxnType.EXPENSE),
                Category(id = "breakfast", name = "早餐", parentId = "food", type = TxnType.EXPENSE),
                Category(id = "salary", name = "工资", type = TxnType.INCOME),
            )
        accountRepo.items["acc-1"] = Account(id = "acc-1", name = "现金", type = AccountType.SAVINGS)
        coEvery { settings.current() } returns AppSettings()
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(): EditorViewModel {
        val vm =
            EditorViewModel(
                txnRepository = txnRepo,
                categoryRepository = categoryRepo,
                accountRepository = accountRepo,
                templateRepository = templateRepo,
                saveTxn = SaveTxnUseCase(txnRepo, categoryRepo),
                settings = settings,
                recordDebtOp = RecordDebtOpUseCase(accountRepo, txnRepo),
                saveDebtAccount = SaveDebtAccountUseCase(accountRepo, RecordDebtOpUseCase(accountRepo, txnRepo)),
            )
        return vm
    }

    @Test
    fun `输入金额并保存写入一笔支出`() =
        runTest(dispatcher.scheduler) {
            val vm = viewModel()
            advanceUntilIdle()
            vm.selectCategory("breakfast")
            vm.digit('2')
            vm.digit('5')
            vm.equals()
            advanceUntilIdle()
            assertEquals("25", vm.state.value.calc.display)
            assertTrue(vm.state.value.calc.evaluated)

            vm.equals() // 第二次等号 = 保存
            advanceUntilIdle()

            assertEquals(1, txnRepo.items.size)
            val txn = txnRepo.items.values.first()
            assertEquals(2500L, txn.amount)
            assertEquals("breakfast", txn.categoryId)
            assertEquals("acc-1", txn.accountId)
            assertTrue(vm.state.value.editingId == null)
        }

    @Test
    fun `保存成功发出Saved事件`() =
        runTest(dispatcher.scheduler) {
            val vm = viewModel()
            advanceUntilIdle()
            vm.selectCategory("breakfast")
            vm.digit('9')

            vm.events.test {
                vm.submit()
                advanceUntilIdle()
                assertEquals(EditorEvent.Saved::class.java, awaitItem()::class.java)
            }
        }

    @Test
    fun `缺少分类时提示并不写库`() =
        runTest(dispatcher.scheduler) {
            val vm = viewModel()
            advanceUntilIdle()
            vm.digit('5')
            vm.submit()
            advanceUntilIdle()

            assertEquals("请选择分类", vm.state.value.error)
            assertTrue(txnRepo.items.isEmpty())
        }

    @Test
    fun `金额为0时提示并不写库`() =
        runTest(dispatcher.scheduler) {
            val vm = viewModel()
            advanceUntilIdle()
            vm.selectCategory("breakfast")
            vm.submit()
            advanceUntilIdle()

            assertEquals("请输入大于 0 的金额", vm.state.value.error)
            assertTrue(txnRepo.items.isEmpty())
        }

    @Test
    fun `切换类型会清空已选分类`() =
        runTest(dispatcher.scheduler) {
            val vm = viewModel()
            advanceUntilIdle()
            vm.selectCategory("breakfast")
            vm.setType(TxnType.INCOME)
            assertNull(vm.state.value.categoryId)
            assertEquals(TxnType.INCOME, vm.state.value.type)
        }

    @Test
    fun `模板带入金额与备注`() =
        runTest(dispatcher.scheduler) {
            templateRepo.items["tpl1"] =
                Template(id = "tpl1", name = "早餐", type = TxnType.EXPENSE, amount = 1250, note = "豆浆油条")
            val vm = viewModel()
            advanceUntilIdle()
            vm.loadTemplate("tpl1")

            assertEquals("12.5", vm.state.value.calc.display)
            assertEquals("豆浆油条", vm.state.value.note)
            assertEquals(1250L, vm.state.value.amountCents)
        }

    @Test
    fun `计算出错时保存被拦截`() =
        runTest(dispatcher.scheduler) {
            val vm = viewModel()
            advanceUntilIdle()
            vm.selectCategory("breakfast")
            vm.digit('8')
            vm.operator(CalcOp.DIV)
            vm.digit('0')
            vm.equals()
            vm.submit()
            advanceUntilIdle()

            assertEquals("不能除以 0", vm.state.value.error)
            assertTrue(txnRepo.items.isEmpty())
        }

    // ---------- v1.8.1：账户预选与「保存免按 =」 ----------

    @Test
    fun `只有一个账户时自动选中，省去无意义的一次点击`() =
        runTest(dispatcher.scheduler) {
            val vm = viewModel()
            advanceUntilIdle()
            assertEquals("acc-1", vm.state.value.accountId)
        }

    @Test
    fun `多个账户时不预选，chip 显示选账户而不是第一个账户名`() =
        runTest(dispatcher.scheduler) {
            accountRepo.items["acc-2"] =
                Account(id = "acc-2", name = "招行卡", type = AccountType.SAVINGS)
            val vm = viewModel()
            advanceUntilIdle()

            assertNull(vm.state.value.accountId, "有多个账户时应等用户自己选")
            assertNull(vm.state.value.accountName)
        }

    @Test
    fun `未选账户时保存提示请选择账户`() =
        runTest(dispatcher.scheduler) {
            accountRepo.items["acc-2"] =
                Account(id = "acc-2", name = "招行卡", type = AccountType.SAVINGS)
            val vm = viewModel()
            advanceUntilIdle()
            vm.selectCategory("breakfast")
            vm.digit('3')
            vm.submitOrEvaluate()
            advanceUntilIdle()

            assertEquals("请选择账户", vm.state.value.error)
            assertTrue(txnRepo.items.isEmpty())
        }

    @Test
    fun `未按等号直接点保存也能存上`() =
        runTest(dispatcher.scheduler) {
            val vm = viewModel()
            advanceUntilIdle()
            vm.selectCategory("breakfast")
            vm.digit('1')
            vm.digit('2')
            vm.digit('8')
            assertFalse(vm.state.value.calc.evaluated, "前置条件：还没求值")

            vm.submitOrEvaluate()
            advanceUntilIdle()

            assertTrue(vm.state.value.calc.evaluated, "保存时应先自动求值")
            assertEquals(1, txnRepo.items.size)
            assertEquals(12_800L, txnRepo.items.values.first().amount)
        }

    @Test
    fun `表达式不完整时点保存不写库`() =
        runTest(dispatcher.scheduler) {
            val vm = viewModel()
            advanceUntilIdle()
            vm.selectCategory("breakfast")
            vm.digit('1')
            vm.digit('0')
            vm.digit('0')
            vm.operator(CalcOp.ADD)

            vm.submitOrEvaluate()
            advanceUntilIdle()

            assertEquals("表达式不完整，请补全金额", vm.state.value.error)
            assertTrue(txnRepo.items.isEmpty())
        }
}
