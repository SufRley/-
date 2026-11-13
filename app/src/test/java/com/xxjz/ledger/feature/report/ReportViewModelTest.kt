package com.xxjz.ledger.feature.report

import app.cash.turbine.test
import com.xxjz.ledger.core.common.DateUtils
import com.xxjz.ledger.domain.model.Category
import com.xxjz.ledger.domain.model.Txn
import com.xxjz.ledger.domain.model.TxnType
import com.xxjz.ledger.testutil.FakeCategoryRepository
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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

/** M5 报表聚合（AC-35 ~ AC-43）。 */
@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("报表页状态机")
class ReportViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val txnRepo = FakeTxnRepository()
    private val categoryRepo = FakeCategoryRepository()

    private val today: LocalDate = DateUtils.today()
    private val monthStart: LocalDate = today.withDayOfMonth(1)
    private val lastMonthAnchor: LocalDate = monthStart.minusMonths(1)

    private var seq = 0

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
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

    private fun put(
        amount: Long,
        type: TxnType,
        date: LocalDate,
        categoryId: String,
        excluded: Boolean = false,
        merchant: String? = null,
        amount2: Long? = null,
    ): String {
        val id = "t${seq++}"
        val time = DateUtils.toMillis(date, LocalTime.NOON)
        txnRepo.items[id] =
            Txn(
                id = id,
                type = type,
                amount = amount2 ?: amount,
                categoryId = categoryId,
                accountId = "acc",
                dateTime = time,
                dateKey = DateUtils.dateKeyOf(date),
                note = id,
                merchantName = merchant,
                excludedFromStats = excluded,
                createdAt = time,
                updatedAt = time,
            )
        return id
    }

    private fun itemsDebug() = txnRepo.items.values.map { it.id + "@" + it.dateTime }

    private fun viewModel() = ReportViewModel(txnRepo, categoryRepo, YearSummaryUseCase(txnRepo, categoryRepo))

    @Test
    @DisplayName("本月支出按大类聚合：小类归到父类，占比合计 100")
    fun rollupToParentCategory() =
        runTest(dispatcher) {
            put(1000, TxnType.EXPENSE, monthStart, "breakfast")
            put(2000, TxnType.EXPENSE, monthStart, "lunch")
            put(7000, TxnType.EXPENSE, monthStart, "traffic")
            val vm = viewModel()
            vm.state.test {
                advanceUntilIdle()
                val s = expectMostRecentItem()
                assertEquals(10_000L, s.summary.expense)
                assertEquals(2, s.summary.ranks.size)
                assertEquals("交通", s.summary.ranks[0].name)
                assertEquals(70, s.summary.ranks[0].percent)
                assertEquals("餐饮", s.summary.ranks[1].name)
                assertEquals(3000L, s.summary.ranks[1].amount)
                assertEquals(30, s.summary.ranks[1].percent)
                assertEquals(100, s.summary.ranks.sumOf { it.percent })
                assertEquals(2, s.summary.ranks[1].count)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    @DisplayName("不计入收支的账目不参与报表（AC-43）")
    fun excludedFromStatsIgnored() =
        runTest(dispatcher) {
            put(5000, TxnType.EXPENSE, monthStart, "traffic")
            put(99_999, TxnType.EXPENSE, monthStart, "traffic", excluded = true)
            val vm = viewModel()
            vm.state.test {
                advanceUntilIdle()
                val s = expectMostRecentItem()
                assertEquals(5000L, s.summary.expense)
                assertEquals(5000L, s.summary.ranks.single().amount)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    @DisplayName("软删除的账目不参与报表")
    fun deletedIgnored() =
        runTest(dispatcher) {
            put(5000, TxnType.EXPENSE, monthStart, "traffic")
            val id = put(12_345, TxnType.EXPENSE, monthStart, "traffic")
            txnRepo.items[id] = txnRepo.items[id]!!.copy(deletedAt = 1L)
            val vm = viewModel()
            vm.state.test {
                advanceUntilIdle()
                assertEquals(5000L, expectMostRecentItem().summary.expense)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    @DisplayName("收入视图：切换类型后排行换成收入分类")
    fun switchToIncome() =
        runTest(dispatcher) {
            put(5000, TxnType.EXPENSE, monthStart, "traffic")
            put(800_000, TxnType.INCOME, monthStart, "salary")
            val vm = viewModel()
            vm.state.test {
                advanceUntilIdle()
                expectMostRecentItem()
                vm.setType(TxnType.INCOME)
                advanceUntilIdle()
                val s = expectMostRecentItem()
                assertEquals(TxnType.INCOME, s.type)
                assertEquals(listOf("工资"), s.summary.ranks.map { it.name })
                // 收支总额始终是两侧都算，不随胶囊切换
                assertEquals(5000L, s.summary.expense)
                assertEquals(800_000L, s.summary.income)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    @DisplayName("月视图趋势按天铺满整月，缺数据补 0")
    fun trendCoversWholeMonth() =
        runTest(dispatcher) {
            put(5000, TxnType.EXPENSE, monthStart, "traffic")
            val vm = viewModel()
            vm.state.test {
                advanceUntilIdle()
                val s = expectMostRecentItem()
                assertEquals(today.lengthOfMonth(), s.summary.trend.size)
                assertEquals(5000L, s.summary.trend[0].expense)
                assertEquals(5000L, s.summary.peak?.expense)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    @DisplayName("翻到上个月只统计上月流水，并给出整月环比")
    fun previousMonth() =
        runTest(dispatcher) {
            put(5000, TxnType.EXPENSE, monthStart, "traffic")
            put(10_000, TxnType.EXPENSE, lastMonthAnchor, "traffic")
            val vm = viewModel()
            vm.state.test {
                advanceUntilIdle()
                expectMostRecentItem()
                vm.previous()
                advanceUntilIdle()
                val s = expectMostRecentItem()
                assertEquals(10_000L, s.summary.expense)
                // 上月整月 vs 上上月整月（无数据）→ 无法比较
                assertNull(s.summary.expenseChange)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    @DisplayName("本月至今 vs 上月同期：月中不会永远是跌")
    fun monthToDateComparison() =
        runTest(dispatcher) {
            // 本月今天花 1_000；上月同一天花 2_000；上月月末另有一笔 90_000 不应进入「同期」对比
            val lastMonth = monthStart.minusDays(1)
            val sameDay = lastMonth.withDayOfMonth(today.dayOfMonth.coerceAtMost(lastMonth.lengthOfMonth()))
            put(1000, TxnType.EXPENSE, today, "traffic")
            put(2000, TxnType.EXPENSE, sameDay, "traffic")
            put(90_000, TxnType.EXPENSE, lastMonth, "traffic")

            val vm = viewModel()
            vm.state.test {
                advanceUntilIdle()
                val s = expectMostRecentItem()
                assertEquals(1000L, s.summary.expense)
                // 上月同期 2000 → 环比 -50%；若拿整月 92000 比会变成 -99%，说明口径用的是「同期」
                assertEquals(-50, s.summary.expenseChange)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    @DisplayName("切换到年视图：趋势 12 个点，按月聚合")
    fun yearViewTrend() =
        runTest(dispatcher) {
            put(5000, TxnType.EXPENSE, monthStart, "traffic")
            val vm = viewModel()
            vm.state.test {
                advanceUntilIdle()
                expectMostRecentItem()
                vm.setPeriod(ReportPeriod.YEAR)
                advanceUntilIdle()
                val s = expectMostRecentItem()
                assertEquals(12, s.summary.trend.size)
                assertEquals(5000L, s.summary.trend[today.monthValue - 1].expense)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    @DisplayName("周视图：只统计本周 7 天")
    fun weekView() =
        runTest(dispatcher) {
            val monday = today.minusDays(((today.dayOfWeek.value + 6) % 7).toLong())
            put(3000, TxnType.EXPENSE, monday, "traffic")
            put(8000, TxnType.EXPENSE, monday.minusDays(1), "traffic")
            val vm = viewModel()
            vm.state.test {
                advanceUntilIdle()
                expectMostRecentItem()
                vm.setPeriod(ReportPeriod.WEEK)
                advanceUntilIdle()
                val s = expectMostRecentItem()
                assertEquals(7, s.summary.trend.size)
                assertEquals(3000L, s.summary.expense)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    @DisplayName("日均 = 支出 ÷ 窗口天数")
    fun dailyAverage() =
        runTest(dispatcher) {
            put((today.lengthOfMonth() * 100L), TxnType.EXPENSE, monthStart, "traffic")
            val vm = viewModel()
            vm.state.test {
                advanceUntilIdle()
                assertEquals(100L, expectMostRecentItem().summary.dailyAverage)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    @DisplayName("年度总结：12 月双柱 + Top3 分类 + Top3 商家 + 去年对比")
    fun yearSummary() =
        runTest(dispatcher) {
            put(10_000, TxnType.EXPENSE, monthStart, "lunch", merchant = "拉面馆")
            put(5000, TxnType.EXPENSE, monthStart, "traffic", merchant = "地铁")
            put(1000, TxnType.EXPENSE, monthStart, "breakfast", merchant = "包子铺")
            put(500, TxnType.EXPENSE, monthStart, "breakfast", merchant = "小店")
            put(900_000, TxnType.INCOME, monthStart, "salary")
            // 去年一笔
            put(4000, TxnType.EXPENSE, monthStart.minusYears(1), "traffic")

            val summary = YearSummaryUseCase(txnRepo, categoryRepo)(today.year)
            assertEquals(today.year, summary.year)
            assertEquals(12, summary.months.size)
            assertEquals(16_500L, summary.expense)
            assertEquals(900_000L, summary.income)
            assertEquals(883_500L, summary.balance)
            // 大类聚合：餐饮（10000+1000+500）> 交通（5000），只有 2 个大类有支出
            assertEquals(2, summary.topCategories.size)
            assertEquals("餐饮", summary.topCategories[0].name)
            assertEquals(11_500L, summary.topCategories[0].amount)
            assertEquals("交通", summary.topCategories[1].name)
            assertEquals(3, summary.topMerchants.size)
            assertEquals(listOf("拉面馆", "地铁", "包子铺"), summary.topMerchants.map { it.name })
            assertEquals(4000L, summary.lastYearExpense)
            assertEquals(313, summary.expenseChange)
        }

    @Test
    @DisplayName("无数据时 hasData 为 false，界面走空状态")
    fun emptyState() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.state.test {
                advanceUntilIdle()
                val s = expectMostRecentItem()
                assertEquals(false, s.summary.hasData)
                assertTrue(s.summary.ranks.isEmpty())
                assertNull(s.summary.peak)
                cancelAndIgnoreRemainingEvents()
            }
        }
}
