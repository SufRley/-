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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

/** M5 P08 分类下钻（AC-38 / AC-39）。 */
@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("分类下钻")
class CategoryDrillViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val txnRepo = FakeTxnRepository()
    private val categoryRepo = FakeCategoryRepository()
    private val today: LocalDate = DateUtils.today()
    private val monthStart: LocalDate = today.withDayOfMonth(1)
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
            )
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun put(
        amount: Long,
        date: LocalDate,
        categoryId: String,
        type: TxnType = TxnType.EXPENSE,
        excluded: Boolean = false,
    ) {
        val id = "t${seq++}"
        val time = DateUtils.toMillis(date, LocalTime.NOON)
        txnRepo.items[id] =
            Txn(
                id = id,
                type = type,
                amount = amount,
                categoryId = categoryId,
                accountId = "acc",
                dateTime = time,
                dateKey = DateUtils.dateKeyOf(date),
                note = "n$id",
                excludedFromStats = excluded,
                createdAt = time,
                updatedAt = time,
            )
    }

    private fun viewModel() = CategoryDrillViewModel(txnRepo, categoryRepo)

    @Test
    @DisplayName("点大类进来：合计该大类下所有小类，并给出二级分解")
    fun drillParentAggregatesChildren() =
        runTest(dispatcher) {
            put(1000, monthStart, "breakfast")
            put(3000, monthStart, "lunch")
            put(9000, monthStart, "traffic")
            val vm = viewModel()
            vm.load("food")
            vm.state.test {
                advanceUntilIdle()
                val s = expectMostRecentItem()
                assertEquals("餐饮", s.title)
                assertEquals(4000L, s.total)
                assertEquals(2, s.rows.size)
                assertEquals(2, s.children.size)
                // 午餐 3000 > 早餐 1000，占比 75/25 合计 100
                assertEquals(listOf("午餐", "早餐"), s.children.map { it.name })
                assertEquals(listOf(75, 25), s.children.map { it.percent })
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    @DisplayName("面包屑：大类 › 小类；只有小类时不留空层级")
    fun breadcrumb() =
        runTest(dispatcher) {
            put(1000, monthStart, "lunch")
            val vm = viewModel()
            vm.load("lunch")
            vm.state.test {
                advanceUntilIdle()
                assertEquals(listOf("餐饮", "午餐"), expectMostRecentItem().breadcrumb)
                cancelAndIgnoreRemainingEvents()
            }
            vm.load("food")
            advanceUntilIdle()
        }

    @Test
    @DisplayName("环比：本月合计 vs 上月同期，涨为负向（支出变多）")
    fun changePercentVsLastMonth() =
        runTest(dispatcher) {
            put(2000, monthStart, "traffic")
            val lastMonth = monthStart.minusMonths(1)
            val sameDay = lastMonth.withDayOfMonth(today.dayOfMonth.coerceAtMost(lastMonth.lengthOfMonth()))
            put(1000, sameDay, "traffic")
            val vm = viewModel()
            vm.load("traffic")
            vm.state.test {
                advanceUntilIdle()
                val s = expectMostRecentItem()
                assertEquals(2000L, s.total)
                assertEquals(1000L, s.prevTotal)
                assertEquals(100, s.changePercent)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    @DisplayName("不计入收支的账目不进入下钻统计（AC-43）")
    fun excludedIgnored() =
        runTest(dispatcher) {
            put(1000, monthStart, "traffic")
            put(50_000, monthStart, "traffic", excluded = true)
            val vm = viewModel()
            vm.load("traffic")
            vm.state.test {
                advanceUntilIdle()
                val s = expectMostRecentItem()
                assertEquals(1000L, s.total)
                assertEquals(1, s.rows.size)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    @DisplayName("该分类本月无数据时 total 为 0、流水为空")
    fun emptyCategory() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.load("traffic")
            vm.state.test {
                advanceUntilIdle()
                val s = expectMostRecentItem()
                assertEquals(0L, s.total)
                assertTrue(s.rows.isEmpty())
                cancelAndIgnoreRemainingEvents()
            }
        }
}
