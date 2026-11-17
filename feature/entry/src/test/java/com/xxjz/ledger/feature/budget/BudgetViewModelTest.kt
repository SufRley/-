package com.xxjz.ledger.feature.budget

import app.cash.turbine.test
import com.xxjz.ledger.core.common.DateUtils
import com.xxjz.ledger.data.datastore.SettingsDataStore
import com.xxjz.ledger.domain.model.AppSettings
import com.xxjz.ledger.domain.model.Budget
import com.xxjz.ledger.domain.model.BudgetScope
import com.xxjz.ledger.domain.model.Category
import com.xxjz.ledger.domain.model.Txn
import com.xxjz.ledger.domain.model.TxnType
import com.xxjz.ledger.domain.usecase.DeleteBudgetUseCase
import com.xxjz.ledger.domain.usecase.GetBudgetForMonthUseCase
import com.xxjz.ledger.testutil.FakeBudgetRepository
import com.xxjz.ledger.testutil.FakeCategoryRepository
import com.xxjz.ledger.testutil.FakeTxnRepository
import io.mockk.coJustRun
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

/** M6 预算首页：卡片数据、排序、切月与删除（AC-44 ~ AC-50）。 */
@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("预算首页")
class BudgetViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val budgetRepo = FakeBudgetRepository()
    private val categoryRepo = FakeCategoryRepository()
    private val txnRepo = FakeTxnRepository()
    private val settingsFlow = MutableStateFlow(AppSettings())
    private val settings = mockk<SettingsDataStore>(relaxed = true)

    private val today: LocalDate = DateUtils.today()
    private val monthKey: Int = DateUtils.monthKeyOf(today)

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { settings.settings } returns settingsFlow
        coJustRun { settings.setSavingGoal(any()) }
        categoryRepo.categories =
            listOf(
                Category(id = "food", name = "餐饮", type = TxnType.EXPENSE),
                Category(id = "breakfast", name = "早餐", parentId = "food", type = TxnType.EXPENSE),
                Category(id = "lunch", name = "午餐", parentId = "food", type = TxnType.EXPENSE),
                Category(id = "traffic", name = "交通", type = TxnType.EXPENSE),
                Category(id = "salary", name = "工资", type = TxnType.INCOME),
            )
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private var seq = 0

    private fun putTxn(
        amount: Long,
        categoryId: String?,
        date: LocalDate = today,
        type: TxnType = TxnType.EXPENSE,
        excluded: Boolean = false,
    ): String {
        val id = "t${seq++}"
        val millis = DateUtils.toMillis(date, LocalTime.NOON)
        txnRepo.items[id] =
            Txn(
                id = id,
                type = type,
                amount = amount,
                categoryId = categoryId,
                accountId = "acc",
                dateTime = millis,
                dateKey = DateUtils.dateKeyOf(date),
                excludedFromStats = excluded,
                createdAt = millis,
                updatedAt = millis,
            )
        return id
    }

    private fun budget(
        id: String,
        name: String,
        amount: Long,
        scope: BudgetScope = BudgetScope.CATEGORY,
        categoryIds: List<String> = emptyList(),
    ) = Budget(id = id, monthKey = monthKey, name = name, scope = scope, categoryIds = categoryIds, amount = amount)

    private fun viewModel() =
        BudgetViewModel(
            budgetRepository = budgetRepo,
            categoryRepository = categoryRepo,
            txnRepository = txnRepo,
            getBudgetForMonth = GetBudgetForMonthUseCase(budgetRepo),
            deleteBudget = DeleteBudgetUseCase(budgetRepo, GetBudgetForMonthUseCase(budgetRepo)),
            settings = settings,
        )

    @Test
    @DisplayName("总预算与分类预算分开，已用金额按分类树展开统计")
    fun splitsTotalAndCategoryBudgets() =
        runTest(dispatcher) {
            putTxn(1000, "breakfast")
            putTxn(2000, "lunch")
            putTxn(5000, "traffic")
            putTxn(9000, "salary", type = TxnType.INCOME)
            budgetRepo.put(budget("total", "本月预算", 300_000, scope = BudgetScope.TOTAL))
            budgetRepo.put(budget("food", "餐饮", 100_000, categoryIds = listOf("food")))

            viewModel().state.test {
                advanceUntilIdle()
                val s = expectMostRecentItem()
                assertEquals(monthKey, s.monthKey)
                assertTrue(s.hasConfig)
                assertNotNull(s.total)
                // 总预算计入全部支出 1000+2000+5000
                assertEquals(8000L, s.total!!.used)
                assertEquals(1, s.categoryBudgets.size)
                // 餐饮预算含早餐 + 午餐
                assertEquals(3000L, s.categoryBudgets.single().used)
                assertEquals("餐饮", s.categoryBudgets.single().categoryLabel)
                assertEquals(9000L, s.monthIncome)
                assertEquals(8000L, s.monthExpense)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    @DisplayName("超支的分类预算排在最前")
    fun overspentFirst() =
        runTest(dispatcher) {
            putTxn(5000, "traffic")
            putTxn(3000, "lunch")
            budgetRepo.put(budget("food", "餐饮", 100_000, categoryIds = listOf("food")))
            // 交通额度 3000，已花 5000 → 超支，应排到餐饮（3000/100000）之前
            budgetRepo.put(budget("traffic", "交通", 3_000, categoryIds = listOf("traffic")))

            viewModel().state.test {
                advanceUntilIdle()
                val s = expectMostRecentItem()
                assertEquals(listOf("交通", "餐饮"), s.categoryBudgets.map { it.budget.name })
                assertTrue(s.categoryBudgets.first().overspent)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    @DisplayName("不计入收支的账目不算进预算已用")
    fun excludedTxnsIgnored() =
        runTest(dispatcher) {
            putTxn(1000, "lunch")
            putTxn(9000, "lunch", excluded = true)
            budgetRepo.put(budget("total", "本月预算", 100_000, scope = BudgetScope.TOTAL))

            viewModel().state.test {
                advanceUntilIdle()
                assertEquals(1000L, expectMostRecentItem().total!!.used)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    @DisplayName("结余目标按本月收入减支出计算进度")
    fun savingGoalProgress() =
        runTest(dispatcher) {
            putTxn(30_000, "salary", type = TxnType.INCOME)
            putTxn(10_000, "lunch")
            settingsFlow.value = AppSettings(savingGoalCents = 40_000)
            budgetRepo.put(budget("total", "本月预算", 100_000, scope = BudgetScope.TOTAL))

            viewModel().state.test {
                advanceUntilIdle()
                val s = expectMostRecentItem()
                assertEquals(40_000L, s.savingGoalCents)
                assertEquals(20_000L, s.monthBalance)
                assertEquals(0.5f, s.savingFraction, 0.001f)
                assertFalse(s.savingAchieved)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    @DisplayName("结余超过目标时标记达成")
    fun savingGoalAchieved() =
        runTest(dispatcher) {
            putTxn(30_000, "salary", type = TxnType.INCOME)
            putTxn(5_000, "lunch")
            settingsFlow.value = AppSettings(savingGoalCents = 10_000)
            budgetRepo.put(budget("total", "本月预算", 100_000, scope = BudgetScope.TOTAL))

            viewModel().state.test {
                advanceUntilIdle()
                val s = expectMostRecentItem()
                assertTrue(s.savingAchieved)
                assertEquals(1f, s.savingFraction, 0.001f)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    @DisplayName("切到没有配置的月份时沿用最近一次配置并标注来源")
    fun inheritsPreviousMonth() =
        runTest(dispatcher) {
            val twoMonthsAgo = BudgetViewModel.shift(monthKey, -2)
            budgetRepo.put(
                Budget(
                    id = "b1",
                    monthKey = twoMonthsAgo,
                    name = "两月前的预算",
                    scope = BudgetScope.TOTAL,
                    amount = 200_000,
                ),
            )

            val vm = viewModel()
            vm.state.test {
                advanceUntilIdle()
                expectMostRecentItem()
                // 上月没有任何配置，会继承两个月前那一版
                vm.previousMonth()
                advanceUntilIdle()
                val s = expectMostRecentItem()
                assertEquals(BudgetViewModel.shift(monthKey, -1), s.monthKey)
                assertEquals(twoMonthsAgo, s.inheritedFrom)
                assertEquals(200_000L, s.total!!.budget.amount)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    @DisplayName("删除后该月不再显示，历史月份保留")
    fun deleteRemovesFromCurrentMonth() =
        runTest(dispatcher) {
            putTxn(1000, "lunch")
            val lastMonth = BudgetViewModel.shift(monthKey, -1)
            budgetRepo.put(
                Budget(
                    id = "b1",
                    monthKey = lastMonth,
                    name = "预算",
                    scope = BudgetScope.CATEGORY,
                    categoryIds = listOf("food"),
                    amount = 100_000,
                ),
            )

            val vm = viewModel()
            vm.state.test {
                advanceUntilIdle()
                val before = expectMostRecentItem()
                assertEquals(1, before.categoryBudgets.size)

                vm.remove("b1")
                advanceUntilIdle()
                val after = expectMostRecentItem()
                assertTrue(after.categoryBudgets.isEmpty())
                assertFalse(after.hasConfig)
                cancelAndIgnoreRemainingEvents()
            }
            // 上月原始记录仍在
            assertEquals(1, budgetRepo.monthRows(lastMonth).size)
            assertEquals(null, budgetRepo.monthRows(lastMonth).single().deletedAt)
        }

    @Test
    @DisplayName("停用预算仍显示但计入停用状态")
    fun disabledBudgetStillListed() =
        runTest(dispatcher) {
            budgetRepo.put(
                budget("food", "餐饮", 100_000, categoryIds = listOf("food")).copy(enabled = false),
            )
            viewModel().state.test {
                advanceUntilIdle()
                val s = expectMostRecentItem()
                assertEquals(BudgetStatus.DISABLED, s.categoryBudgets.single().status)
                cancelAndIgnoreRemainingEvents()
            }
        }
}
