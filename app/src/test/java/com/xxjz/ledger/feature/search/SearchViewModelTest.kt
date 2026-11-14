package com.xxjz.ledger.feature.search

import app.cash.turbine.test
import com.xxjz.ledger.core.common.DateUtils
import com.xxjz.ledger.data.datastore.SettingsDataStore
import com.xxjz.ledger.domain.model.Category
import com.xxjz.ledger.domain.model.Txn
import com.xxjz.ledger.domain.model.TxnType
import com.xxjz.ledger.testutil.FakeCategoryRepository
import com.xxjz.ledger.testutil.FakeTxnRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
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

/** M4 搜索 ViewModel：防抖、筛选组合、历史（AC-28 ~ AC-31）。 */
@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("搜索页状态机")
class SearchViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val txnRepo = FakeTxnRepository()
    private val categoryRepo = FakeCategoryRepository()
    private val settings = mockk<SettingsDataStore>(relaxed = true)

    private val today = DateUtils.today()
    private val inMonthTime = DateUtils.toMillis(today, java.time.LocalTime.of(12, 0))
    private val lastMonthTime = DateUtils.toMillis(today.minusMonths(1).withDayOfMonth(1), java.time.LocalTime.NOON)

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { settings.searchHistory } returns flowOf(listOf("拉面"))
        categoryRepo.categories =
            listOf(
                Category(id = "food", name = "餐饮", type = TxnType.EXPENSE),
                Category(id = "salary", name = "工资", type = TxnType.INCOME),
            )

        fun txn(
            id: String,
            amount: Long,
            note: String,
            type: TxnType,
            at: Long,
            categoryId: String,
        ) = Txn(
            id = id,
            type = type,
            amount = amount,
            categoryId = categoryId,
            accountId = "acc",
            dateTime = at,
            dateKey = DateUtils.dateKeyOf(at),
            note = note,
            createdAt = at,
            updatedAt = at,
        )
        txnRepo.items["a"] = txn("a", 2800, "午饭 拉面", TxnType.EXPENSE, inMonthTime, "food")
        txnRepo.items["b"] = txn("b", 900_000, "工资", TxnType.INCOME, inMonthTime, "salary")
        txnRepo.items["c"] = txn("c", 1500, "晚饭 拉面", TxnType.EXPENSE, lastMonthTime, "food")
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = SearchViewModel(txnRepo, categoryRepo, settings)

    @Test
    @DisplayName("初始只展示本月的全部记录，合计等于金额之和")
    fun initialStateShowsThisMonth() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.state.test {
                advanceUntilIdle()
                val s = expectMostRecentItem()
                assertEquals(SearchRangeOption.THIS_MONTH, s.range)
                assertEquals(2, s.count)
                assertEquals(2800L + 900_000L, s.totalCents)
                assertEquals(listOf("拉面"), s.history)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    @DisplayName("关键词 300ms 防抖：连续输入只触发最后一次")
    fun queryIsDebounced() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.state.test {
                advanceUntilIdle()
                var latest = expectMostRecentItem()

                vm.setQuery("拉")
                advanceTimeBy(100)
                vm.setQuery("拉面")
                advanceTimeBy(100)
                vm.setQuery("拉面饭")
                // 未满 300ms，结果不应该变成最后一次输入
                advanceTimeBy(200)
                latest = latestState(latest)
                assertTrue(latest.query != "拉面饭", "防抖窗口内不应立即更新为最后输入")

                // 超过防抖窗口后结果跟上最后输入
                advanceTimeBy(400)
                advanceUntilIdle()
                latest = latestState(latest)
                assertEquals("拉面饭", latest.query)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    @DisplayName("关键词命中备注并带出分类名")
    fun keywordMatchesNote() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.state.test {
                advanceUntilIdle()
                expectMostRecentItem()
                vm.setQuery("拉面")
                // 跨过防抖 + 组合流
                advanceTimeBy(500)
                advanceUntilIdle()
                val s = expectMostRecentItem()
                assertEquals(1, s.count)
                assertEquals("午饭 拉面", s.results.first().txn.note)
                assertEquals("餐饮", s.results.first().categoryName)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    @DisplayName("切换到全部时间后上月记录也出现")
    fun switchRangeToAll() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.state.test {
                advanceUntilIdle()
                expectMostRecentItem()
                vm.setRange(SearchRangeOption.ALL)
                advanceUntilIdle()
                val s = expectMostRecentItem()
                assertEquals(3, s.count)
                assertEquals(2800L + 900_000L + 1500L, s.totalCents)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    @DisplayName("类型筛选只保留支出")
    fun filterByType() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.state.test {
                advanceUntilIdle()
                expectMostRecentItem()
                vm.setType(TxnType.EXPENSE)
                advanceUntilIdle()
                val s = expectMostRecentItem()
                assertEquals(1, s.count)
                assertEquals(2800L, s.totalCents)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    @DisplayName("金额输入只保留数字与小数点，且按元换算为分参与过滤")
    fun amountInputSanitizedAndConverted() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.state.test {
                advanceUntilIdle()
                var latest = expectMostRecentItem()

                vm.setMin("2a8.5元")
                advanceUntilIdle()
                latest = latestState(latest)
                assertEquals("28.5", latest.minText)
                // 28.5 元以上只剩工资
                assertEquals(1, latest.count)
                assertEquals(900_000L, latest.totalCents)

                vm.setMin("")
                vm.setMax("20")
                advanceUntilIdle()
                latest = latestState(latest)
                assertEquals("", latest.minText)
                assertEquals("20", latest.maxText)
                // 20 元以下含午饭 2800 分（28 元 > 20 元不算）
                assertEquals(0, latest.count)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    @DisplayName("清空筛选回到默认：无类型、本月、无金额")
    fun clearFiltersResetsDefaults() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.state.test {
                advanceUntilIdle()
                expectMostRecentItem()
                vm.setType(TxnType.INCOME)
                vm.setRange(SearchRangeOption.LAST_7_DAYS)
                vm.setMin("5")
                advanceUntilIdle()
                expectMostRecentItem()

                vm.clearFilters()
                advanceUntilIdle()
                val s = expectMostRecentItem()
                assertNull(s.type)
                assertEquals(SearchRangeOption.THIS_MONTH, s.range)
                assertEquals("", s.minText)
                assertEquals("", s.maxText)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    @DisplayName("提交关键词写入历史，空关键词不写")
    fun submitQueryWritesHistory() =
        runTest(dispatcher) {
            coEvery { settings.addSearchKeyword(any()) } returns Unit
            val vm = viewModel()
            vm.setQuery("  地铁  ")
            vm.submitQuery()
            advanceUntilIdle()
            coVerify { settings.addSearchKeyword("地铁") }

            vm.setQuery("   ")
            vm.submitQuery()
            advanceUntilIdle()
            coVerify(exactly = 1) { settings.addSearchKeyword(any()) }
        }

    /**
     * 取队列中最新状态；没有新发射时沿用 [fallback]。
     *
     * 直接用 `expectMostRecentItem()` 在队列为空时会抛异常，而防抖窗口内的「不该变」断言
     * 恰恰要求保持原状态，所以这里先尝试取出、取不到就沿用旧值。
     */
    private suspend fun app.cash.turbine.ReceiveTurbine<SearchUiState>.latestState(fallback: SearchUiState): SearchUiState =
        runCatching { expectMostRecentItem() }.getOrNull() ?: fallback
}
