package com.xxjz.ledger.feature.editor

import app.cash.turbine.test
import com.xxjz.ledger.data.datastore.AppSettings
import com.xxjz.ledger.domain.model.Account
import com.xxjz.ledger.domain.model.AccountType
import com.xxjz.ledger.domain.model.Category
import com.xxjz.ledger.domain.model.Tag
import com.xxjz.ledger.domain.model.Template
import com.xxjz.ledger.domain.model.TxnType
import com.xxjz.ledger.domain.usecase.RecordDebtOpUseCase
import com.xxjz.ledger.domain.usecase.SaveDebtAccountUseCase
import com.xxjz.ledger.domain.usecase.SaveTxnUseCase
import com.xxjz.ledger.testutil.FakeAccountRepository
import com.xxjz.ledger.testutil.FakeCategoryRepository
import com.xxjz.ledger.testutil.FakeSettingsDataStore
import com.xxjz.ledger.testutil.FakeTagRepository
import com.xxjz.ledger.testutil.FakeTemplateRepository
import com.xxjz.ledger.testutil.FakeTxnRepository
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
    private val tagRepo = FakeTagRepository()
    private val settings = FakeSettingsDataStore()

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
                tagRepository = tagRepo,
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
    fun `该类型下一个分类都没有时提示并不写库`() =
        runTest(dispatcher.scheduler) {
            // v1.9.3 起新建会自动预选分类，只有「确实没有分类可选」才会落到这个分支
            categoryRepo.categories = emptyList()
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
    fun `切换类型会换成新类型的分类，不残留支出分类`() =
        runTest(dispatcher.scheduler) {
            val vm = viewModel()
            advanceUntilIdle()
            vm.selectCategory("breakfast")
            vm.setType(TxnType.INCOME)

            assertEquals(TxnType.INCOME, vm.state.value.type)
            // 收入只有一个「工资」，切过去应预选它，且绝不能还是「早餐」
            assertEquals("salary", vm.state.value.categoryId)
        }

    @Test
    fun `切走再切回能恢复刚才选的分类`() =
        runTest(dispatcher.scheduler) {
            val vm = viewModel()
            advanceUntilIdle()
            vm.selectCategory("breakfast")

            vm.setType(TxnType.TRANSFER)
            assertNull(vm.state.value.categoryId)

            vm.setType(TxnType.EXPENSE)
            assertEquals("breakfast", vm.state.value.categoryId)
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

    // ---------- v1.9.1 第 5 条：全屏记账页新增的数据行为 ----------

    @Test
    fun `切换类型会清掉组合分摊，避免残留金额悄悄生效`() =
        runTest(dispatcher.scheduler) {
            val vm = viewModel()
            advanceUntilIdle()
            vm.setSplitMode(true)
            vm.setSplitAmount("acc-1", "30")
            assertEquals(3000L, vm.state.value.splitTotal)

            vm.setType(TxnType.TRANSFER)

            assertFalse(vm.state.value.splitMode)
            assertTrue(vm.state.value.splits.isEmpty())
        }

    @Test
    fun `组合支付按账户各写一条流水并标注份数`() =
        runTest(dispatcher.scheduler) {
            accountRepo.items["acc-2"] = Account(id = "acc-2", name = "微信", type = AccountType.SAVINGS)
            val vm = viewModel()
            advanceUntilIdle()
            vm.selectCategory("breakfast")
            vm.setNote("午饭")
            vm.setSplitMode(true)
            vm.setSplitAmount("acc-1", "30")
            vm.setSplitAmount("acc-2", "20")

            vm.submitOrEvaluate()
            advanceUntilIdle()

            assertEquals(2, txnRepo.items.size)
            assertEquals(listOf(3000L, 2000L), txnRepo.items.values.map { it.amount }.sortedDescending())
            assertTrue(txnRepo.items.values.all { it.note.contains("组合") })
        }

    @Test
    fun `组合支付只填一个账户时拒绝保存`() =
        runTest(dispatcher.scheduler) {
            val vm = viewModel()
            advanceUntilIdle()
            vm.selectCategory("breakfast")
            vm.setSplitMode(true)
            vm.setSplitAmount("acc-1", "30")

            vm.submitOrEvaluate()
            advanceUntilIdle()

            assertTrue(txnRepo.items.isEmpty())
            assertEquals("组合支付至少要两个账户各出一部分", vm.state.value.error)
        }

    @Test
    fun `清空某个账户的分摊金额等于把它从组合里去掉`() =
        runTest(dispatcher.scheduler) {
            val vm = viewModel()
            advanceUntilIdle()
            vm.setSplitMode(true)
            vm.setSplitAmount("acc-1", "30")
            vm.setSplitAmount("acc-1", "")

            assertTrue(vm.state.value.splits.isEmpty())
        }

    @Test
    fun `快捷分类按使用次数排序，次数相同按原有顺序`() =
        runTest(dispatcher.scheduler) {
            categoryRepo.categories =
                listOf(
                    Category(id = "c1", name = "一", type = TxnType.EXPENSE, sortOrder = 1, usageCount = 0),
                    Category(id = "c2", name = "二", type = TxnType.EXPENSE, sortOrder = 2, usageCount = 9),
                    Category(id = "c3", name = "三", type = TxnType.EXPENSE, sortOrder = 3, usageCount = 0),
                    Category(id = "i1", name = "收", type = TxnType.INCOME, usageCount = 99),
                )
            val vm = viewModel()
            advanceUntilIdle()

            assertEquals(listOf("c2", "c1", "c3"), vm.state.value.quickCategories.map { it.id })
        }

    @Test
    fun `快捷分类只取前12个，刚好三行四列`() =
        runTest(dispatcher.scheduler) {
            categoryRepo.categories =
                (1..20).map { Category(id = "c$it", name = "$it", type = TxnType.EXPENSE, sortOrder = it) }
            val vm = viewModel()
            advanceUntilIdle()

            assertEquals(QUICK_CATEGORY_SLOTS, vm.state.value.quickCategories.size)
            assertEquals(12, QUICK_CATEGORY_SLOTS)
        }

    @Test
    fun `标签可以来回切换`() =
        runTest(dispatcher.scheduler) {
            val vm = viewModel()
            advanceUntilIdle()
            vm.toggleTag("t1")
            assertTrue("t1" in vm.state.value.selectedTagIds)
            vm.toggleTag("t1")
            assertFalse("t1" in vm.state.value.selectedTagIds)
        }

    @Test
    fun `保存时把选中的标签挂到流水上`() =
        runTest(dispatcher.scheduler) {
            tagRepo.items["t1"] = Tag(id = "t1", name = "出差")
            val vm = viewModel()
            advanceUntilIdle()
            vm.selectCategory("breakfast")
            vm.digit('5')
            vm.toggleTag("t1")

            vm.submitOrEvaluate()
            advanceUntilIdle()

            val txnId = txnRepo.items.keys.first()
            assertEquals(listOf("t1"), tagRepo.linksOf(txnId))
        }

    @Test
    fun `编辑已有账目会回填照片与标签`() =
        runTest(dispatcher.scheduler) {
            categoryRepo.categories = listOf(Category(id = "food", name = "餐饮", type = TxnType.EXPENSE))
            val vm = viewModel()
            advanceUntilIdle()
            vm.selectCategory("food")
            vm.digit('9')
            vm.submitOrEvaluate()
            advanceUntilIdle()
            val saved = txnRepo.items.values.first()
            txnRepo.items[saved.id] = saved.copy(photoPath = "/tmp/r.jpg")
            tagRepo.setTxnTags(saved.id, listOf("t1"))

            val editor = viewModel()
            editor.loadForEdit(saved.id)

            assertEquals("/tmp/r.jpg", editor.state.value.photoPath)
            assertEquals(setOf("t1"), editor.state.value.selectedTagIds)
            assertEquals(saved.id, editor.state.value.editingId)
        }

    @Test
    fun `编辑一笔组合账目会把原流水移出明细`() =
        runTest(dispatcher.scheduler) {
            val vm = viewModel()
            advanceUntilIdle()
            vm.selectCategory("breakfast")
            vm.digit('4')
            vm.submitOrEvaluate()
            advanceUntilIdle()
            val original = txnRepo.items.values.first()

            // 第二个账户必须在第一笔存完之后再加：只有一个账户时会被自动预选，
            // 先加了就会在没有「唯一账户」的前提下保存失败
            accountRepo.items["acc-2"] = Account(id = "acc-2", name = "微信", type = AccountType.SAVINGS)
            val editor = viewModel()
            editor.loadForEdit(original.id)
            editor.setSplitMode(true)
            editor.setSplitAmount("acc-1", "25")
            editor.setSplitAmount("acc-2", "15")
            editor.submitOrEvaluate()
            advanceUntilIdle()

            val alive = txnRepo.items.values.filter { it.deletedAt == null }
            assertFalse(alive.any { it.id == original.id }, "原流水应被软删除")
            assertEquals(2, alive.size)
        }

    // ---------- v1.9.3 第 2 条：新建时的分类与账户记忆 ----------

    @Test
    fun `新建时按上次用的分类预选`() =
        runTest(dispatcher.scheduler) {
            settings.replaceAll(AppSettings(lastExpenseCategoryId = "breakfast"))
            val vm = viewModel()
            advanceUntilIdle()

            assertEquals("breakfast", vm.state.value.categoryId)
            assertFalse(vm.state.value.dirty)
        }

    @Test
    fun `记忆的分类已失效时退到该类型最高频的分类`() =
        runTest(dispatcher.scheduler) {
            settings.replaceAll(AppSettings(lastExpenseCategoryId = "已经删掉的分类"))
            categoryRepo.categories =
                listOf(
                    Category(id = "a", name = "A", type = TxnType.EXPENSE, sortOrder = 1, usageCount = 0),
                    Category(id = "b", name = "B", type = TxnType.EXPENSE, sortOrder = 2, usageCount = 9),
                    Category(id = "c", name = "C", type = TxnType.EXPENSE, sortOrder = 3, usageCount = 0),
                )
            val vm = viewModel()
            advanceUntilIdle()

            assertEquals("b", vm.state.value.categoryId)
        }

    @Test
    fun `收入不会被支出分类的记忆污染`() =
        runTest(dispatcher.scheduler) {
            settings.replaceAll(
                AppSettings(lastExpenseCategoryId = "breakfast", lastIncomeCategoryId = "salary"),
            )
            val vm = viewModel()
            advanceUntilIdle()

            vm.setType(TxnType.INCOME)
            assertEquals("salary", vm.state.value.categoryId)

            vm.setType(TxnType.EXPENSE)
            assertEquals("breakfast", vm.state.value.categoryId)
        }

    @Test
    fun `账户优先用上次实际用的那个，而不是设置里的默认账户`() =
        runTest(dispatcher.scheduler) {
            accountRepo.items["acc-2"] = Account(id = "acc-2", name = "微信", type = AccountType.SAVINGS)
            settings.replaceAll(AppSettings(lastPayAccountId = "acc-2", defaultAccountId = "acc-1"))
            val vm = viewModel()
            advanceUntilIdle()

            assertEquals("acc-2", vm.state.value.accountId)
        }

    @Test
    fun `上次用的账户已被删除时回退到默认账户`() =
        runTest(dispatcher.scheduler) {
            accountRepo.items["acc-2"] = Account(id = "acc-2", name = "微信", type = AccountType.SAVINGS)
            settings.replaceAll(AppSettings(lastPayAccountId = "已删除", defaultAccountId = "acc-2"))
            val vm = viewModel()
            advanceUntilIdle()

            assertEquals("acc-2", vm.state.value.accountId)
        }

    @Test
    fun `只有一个资金账户时自动预选它`() =
        runTest(dispatcher.scheduler) {
            val vm = viewModel()
            advanceUntilIdle()

            assertEquals("acc-1", vm.state.value.accountId)
        }

    @Test
    fun `多个账户且没有任何记忆时不预选，避免猜错`() =
        runTest(dispatcher.scheduler) {
            accountRepo.items["acc-2"] = Account(id = "acc-2", name = "微信", type = AccountType.SAVINGS)
            val vm = viewModel()
            advanceUntilIdle()

            assertNull(vm.state.value.accountId)
        }

    @Test
    fun `借贷账户不会被当成付款账户预选`() =
        runTest(dispatcher.scheduler) {
            accountRepo.items.clear()
            accountRepo.items["debt-1"] = Account(id = "debt-1", name = "张三", type = AccountType.DEBT)
            val vm = viewModel()
            advanceUntilIdle()

            assertNull(vm.state.value.accountId)
        }

    @Test
    fun `新建保存后记住分类与账户`() =
        runTest(dispatcher.scheduler) {
            val vm = viewModel()
            advanceUntilIdle()
            vm.selectCategory("breakfast")
            vm.digit('5')
            vm.submitOrEvaluate()
            advanceUntilIdle()

            assertEquals("breakfast", settings.value.lastExpenseCategoryId)
            assertEquals("acc-1", settings.value.lastPayAccountId)
            assertEquals(TxnType.EXPENSE, settings.value.lastTxnType)
        }

    @Test
    fun `编辑保存不覆盖记忆`() =
        runTest(dispatcher.scheduler) {
            val vm = viewModel()
            advanceUntilIdle()
            vm.selectCategory("breakfast")
            vm.digit('5')
            vm.submitOrEvaluate()
            advanceUntilIdle()
            val saved = txnRepo.items.values.first()

            settings.replaceAll(settings.value.copy(lastExpenseCategoryId = "food"))
            val editor = viewModel()
            editor.loadForEdit(saved.id)
            editor.submitOrEvaluate()
            advanceUntilIdle()

            assertEquals("food", settings.value.lastExpenseCategoryId)
        }

    @Test
    fun `编辑打开时以原账目的分类为准，不吃记忆`() =
        runTest(dispatcher.scheduler) {
            val vm = viewModel()
            advanceUntilIdle()
            vm.selectCategory("breakfast")
            vm.digit('5')
            vm.submitOrEvaluate()
            advanceUntilIdle()
            val saved = txnRepo.items.values.first()

            settings.replaceAll(AppSettings(lastExpenseCategoryId = "food"))
            val editor = viewModel()
            editor.loadForEdit(saved.id)

            assertEquals("breakfast", editor.state.value.categoryId)
        }

    @Test
    fun `组合支付记住出钱最多的那个账户`() =
        runTest(dispatcher.scheduler) {
            accountRepo.items["acc-2"] = Account(id = "acc-2", name = "微信", type = AccountType.SAVINGS)
            val vm = viewModel()
            advanceUntilIdle()
            vm.selectCategory("breakfast")
            vm.setSplitMode(true)
            vm.setSplitAmount("acc-1", "30")
            vm.setSplitAmount("acc-2", "50")
            vm.submitOrEvaluate()
            advanceUntilIdle()

            assertEquals("acc-2", settings.value.lastPayAccountId)
        }
}
