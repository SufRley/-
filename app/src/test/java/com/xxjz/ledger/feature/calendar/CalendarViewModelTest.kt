package com.xxjz.ledger.feature.calendar

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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

/** M4 日历 ViewModel：按天聚合、只统计支出、排除不计入（AC-32 / AC-33 / AC-12）。 */
@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("日历页状态机")
class CalendarViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val txnRepo = FakeTxnRepository()
    private val categoryRepo = FakeCategoryRepository()

    private val today: LocalDate = DateUtils.today()
    private val month: LocalDate = today.withDayOfMonth(1)

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        categoryRepo.categories =
            listOf(
                Category(id = "food", name = "餐饮", type = TxnType.EXPENSE),
                Category(id = "salary", name = "工资", type = TxnType.INCOME),
            )

        fun txn(
            id: String,
            amount: Long,
            type: TxnType,
            date: LocalDate,
            categoryId: String,
            excluded: Boolean = false,
        ) = Txn(
            id = id,
            type = type,
            amount = amount,
            categoryId = categoryId,
            accountId = "acc",
            dateTime = DateUtils.toMillis(date, LocalTime.NOON),
            dateKey = DateUtils.dateKeyOf(date),
            note = id,
            excludedFromStats = excluded,
            createdAt = 1L,
            updatedAt = 1L,
        )
        txnRepo.items["d1a"] = txn("d1a", 2000, TxnType.EXPENSE, month, "food")
        txnRepo.items["d1b"] = txn("d1b", 500, TxnType.EXPENSE, month, "food")
        txnRepo.items["d1c"] = txn("d1c", 100_000, TxnType.INCOME, month, "salary")
        txnRepo.items["d1x"] = txn("d1x", 9999, TxnType.EXPENSE, month, "food", excluded = true)
        // 邻近月份，不应出现在本月网格
        txnRepo.items["prev"] = txn("prev", 7777, TxnType.EXPENSE, month.minusDays(1), "food")
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = CalendarViewModel(txnRepo, categoryRepo)

    @Test
    @DisplayName("网格按周切分，每行 7 格，本月天数与日历一致")
    fun gridShape() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.state.test {
                advanceUntilIdle()
                val s = expectMostRecentItem()
                assertFalse(s.loading)
                assertTrue(s.weeks.isNotEmpty())
                s.weeks.forEach { assertEquals(7, it.size) }
                assertEquals(month.lengthOfMonth(), s.weeks.flatten().count { it.date != null })
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    @DisplayName("同一天多笔支出累加，收入单独统计，不计入收支的被排除")
    fun dailyAggregation() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.state.test {
                advanceUntilIdle()
                val s = expectMostRecentItem()
                val cell = s.weeks.flatten().first { it.date == month }
                assertEquals(2500L, cell.expense)
                assertEquals(100_000L, cell.income)
                // 本月总支出不含 excluded 的 9999
                assertEquals(2500L, s.monthExpense)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    @DisplayName("今天格子被标记")
    fun todayFlagged() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.state.test {
                advanceUntilIdle()
                val s = expectMostRecentItem()
                assertEquals(today, s.selected)
                val cell = s.weeks.flatten().firstOrNull { it.date == today }
                assertTrue(cell != null && cell.isToday)
                assertEquals(1, s.weeks.flatten().count { it.isToday })
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    @DisplayName("选中某天后当天的流水按时间倒序展示并带分类名")
    fun selectDayShowsRows() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.state.test {
                advanceUntilIdle()
                expectMostRecentItem()
                vm.select(month)
                advanceUntilIdle()
                val s = expectMostRecentItem()
                assertEquals(month, s.selected)
                assertEquals(4, s.dayRows.size)
                assertEquals("餐饮", s.dayRows.first().categoryName)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    @DisplayName("翻月后标题与网格随之变化，回到今天可复位")
    fun monthNavigation() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.state.test {
                advanceUntilIdle()
                var cur = expectMostRecentItem()
                val initial = cur.monthLabel

                vm.nextMonth()
                advanceUntilIdle()
                cur = latestState(cur)
                assertTrue(cur.monthLabel != initial, "翻到下月后标题应变化")
                // 下个月没有本月流水
                assertEquals(0L, cur.monthExpense)
                assertTrue(cur.weeks.flatten().none { it.date == month })

                vm.previousMonth()
                advanceUntilIdle()
                cur = latestState(cur)
                assertEquals(initial, cur.monthLabel)

                // 连翻两月后回到今天，应复位到本月并选中今天
                vm.nextMonth()
                vm.nextMonth()
                advanceUntilIdle()
                cur = latestState(cur)
                vm.selectToday()
                advanceUntilIdle()
                cur = latestState(cur)
                assertEquals(initial, cur.monthLabel)
                assertEquals(today, cur.selected)
                assertEquals(today, DateUtils.today())
                cancelAndIgnoreRemainingEvents()
            }
        }

    /**
     * 取队列中最新状态；没有新发射时沿用 [fallback]。
     *
     * `expectMostRecentItem()` 在队列为空时会抛异常，而翻月测试里存在「连续切换只产生一次发射」的情况，
     * 所以这里取不到就沿用旧值。
     */
    private suspend fun app.cash.turbine.ReceiveTurbine<CalendarUiState>.latestState(fallback: CalendarUiState): CalendarUiState {
        var last = fallback
        while (true) {
            var item: CalendarUiState? = null
            try {
                item = expectMostRecentItem()
            } catch (e: AssertionError) {
                break
            }
            last = item ?: break
        }
        return last
    }
}
