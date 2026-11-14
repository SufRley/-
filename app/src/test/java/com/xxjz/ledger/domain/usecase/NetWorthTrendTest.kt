package com.xxjz.ledger.domain.usecase

import com.xxjz.ledger.data.local.TestDatabaseFactory
import com.xxjz.ledger.data.local.db.AppDatabase
import com.xxjz.ledger.data.local.entity.AccountEntity
import com.xxjz.ledger.data.local.entity.CategoryEntity
import com.xxjz.ledger.data.repository.AccountRepositoryImpl
import com.xxjz.ledger.data.repository.AssetEventStore
import com.xxjz.ledger.data.repository.CategoryRepositoryImpl
import com.xxjz.ledger.data.repository.TxnRepositoryImpl
import com.xxjz.ledger.domain.model.AccountType
import com.xxjz.ledger.domain.model.Txn
import com.xxjz.ledger.domain.model.TxnType
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 净资产趋势与变化记录（PRD 5.2 板块③④）。
 *
 * 跑真实 Room + 真实仓库 + 真实 UseCase：这两块的核心风险不是「算错一个数」，
 * 而是「用了错误的查询」——比如趋势少读回收站、变化记录漏掉元事件。
 */
@RunWith(RobolectricTestRunner::class)
class NetWorthTrendTest {
    private lateinit var db: AppDatabase
    private lateinit var accountRepo: AccountRepositoryImpl
    private lateinit var txnRepo: TxnRepositoryImpl
    private lateinit var categoryRepo: CategoryRepositoryImpl
    private lateinit var eventStore: AssetEventStore

    private val today = LocalDate.of(2026, 11, 14)

    @Before
    fun setUp() {
        db = TestDatabaseFactory.create()
        eventStore = AssetEventStore(db.metaDao())
        accountRepo = AccountRepositoryImpl(db, db.accountDao(), eventStore)
        txnRepo = TxnRepositoryImpl(db.txnDao(), db.categoryDao(), accountRepo)
        categoryRepo = CategoryRepositoryImpl(db.categoryDao(), db.txnDao())

        kotlinx.coroutines.runBlocking {
            db.accountDao().insertAll(
                listOf(
                    account("cash", AccountType.CASH, initial = 1_000_00),
                ),
            )
            db.categoryDao().insertAll(
                listOf(
                    CategoryEntity(
                        id = "c_food",
                        name = "餐饮",
                        parentId = null,
                        type = "EXPENSE",
                        icon = "",
                        color = "",
                        sortOrder = 0,
                        hidden = false,
                        pinned = false,
                        usageCount = 0,
                    ),
                ),
            )
        }
    }

    @After
    fun tearDown() {
        if (this::db.isInitialized) db.close()
    }

    private fun account(
        id: String,
        type: AccountType,
        initial: Long = 0,
    ) = AccountEntity(
        id = id,
        name = id,
        type = type.name,
        initialBalance = initial,
        icon = "",
        color = "",
        cardLimit = null,
        billDay = null,
        repayDay = null,
        hidden = false,
        sortOrder = 0,
    )

    private fun at(
        year: Int,
        month: Int,
        day: Int,
    ): Long = LocalDateTime.of(year, month, day, 12, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun txn(
        id: String,
        type: TxnType,
        amount: Long,
        millis: Long,
        note: String = "",
        categoryId: String? = null,
        refundOf: String? = null,
        parentTxnId: String? = null,
    ) = Txn(
        id = id,
        type = type,
        amount = amount,
        categoryId = categoryId,
        accountId = "cash",
        dateTime = millis,
        dateKey = com.xxjz.ledger.core.common.DateUtils.dateKeyOf(millis),
        note = note,
        refundOfTxnId = refundOf,
        parentTxnId = parentTxnId,
        createdAt = millis,
        updatedAt = millis,
    )

    private fun trendUseCase() = GetNetWorthTrendUseCase(accountRepo, txnRepo, eventStore)

    private fun changesUseCase() = GetAssetChangesUseCase(accountRepo, txnRepo, categoryRepo, eventStore)

    // ---------- 趋势 ----------

    @Test
    fun monthViewHasTwelvePoints() {
        val trend = kotlinx.coroutines.runBlocking { trendUseCase().current(TrendPeriod.MONTH, today) }
        assertEquals(12, trend.points.size)
        assertEquals("11月", trend.points.last().label)
        assertEquals("12月", trend.points.first().label)
    }

    @Test
    fun yearViewHasFivePoints() {
        val trend = kotlinx.coroutines.runBlocking { trendUseCase().current(TrendPeriod.YEAR, today) }
        assertEquals(5, trend.points.size)
        assertEquals(listOf("2022", "2023", "2024", "2025", "2026"), trend.points.map { it.label })
    }

    @Test
    fun trendMatchesReplayOfTxns() {
        kotlinx.coroutines.runBlocking {
            // 初始 1000，9 月花 200，10 月收入 500
            txnRepo.insert(txn("t1", TxnType.EXPENSE, 200_00, at(2026, 9, 10), categoryId = "c_food"))
            txnRepo.insert(txn("t2", TxnType.INCOME, 500_00, at(2026, 10, 5)))

            val trend = trendUseCase().current(TrendPeriod.MONTH, today)
            val sep = trend.points.first { it.label == "9月" }
            val oct = trend.points.first { it.label == "10月" }
            assertEquals(800_00L, sep.net, "9 月末应是 1000 − 200")
            assertEquals(1_300_00L, oct.net, "10 月末应是 800 + 500")
        }
    }

    @Test
    fun transferDoesNotChangeTrend() {
        kotlinx.coroutines.runBlocking {
            db.accountDao().insertAll(listOf(account("sav", AccountType.SAVINGS)))
            txnRepo.insert(
                Txn(
                    id = "tr1",
                    type = TxnType.TRANSFER,
                    amount = 300_00,
                    categoryId = null,
                    accountId = "cash",
                    toAccountId = "sav",
                    dateTime = at(2026, 10, 5),
                    dateKey = 20261005,
                    createdAt = at(2026, 10, 5),
                    updatedAt = at(2026, 10, 5),
                ),
            )
            val trend = trendUseCase().current(TrendPeriod.MONTH, today)
            assertTrue(trend.points.all { it.net == 1_000_00L }, "账户间转账不改变净资产")
        }
    }

    @Test
    fun deletedTxnRolledBack() =
        kotlinx.coroutines.runBlocking {
            val expenseAt = at(2026, 9, 10)
            val deleteAt = at(2026, 10, 1)
            txnRepo.insert(txn("t1", TxnType.EXPENSE, 200_00, expenseAt, categoryId = "c_food"))
            txnRepo.softDelete("t1", deleteAt)

            val trend = trendUseCase().current(TrendPeriod.MONTH, today)
            val sep = trend.points.first { it.label == "9月" }
            val oct = trend.points.first { it.label == "10月" }
            assertEquals(800_00L, sep.net, "9 月当时确实花了钱")
            assertEquals(1_000_00L, oct.net, "10 月删除后净资产回到原值")
        }

    @Test
    fun marketValueEventShiftsTrend() =
        kotlinx.coroutines.runBlocking {
            db.accountDao().insertAll(listOf(account("inv", AccountType.INVEST)))
            accountRepo.updateMarketValue("inv", 5_000_00, at(2026, 10, 20))

            val trend = trendUseCase().current(TrendPeriod.MONTH, today)
            val sep = trend.points.first { it.label == "9月" }
            val oct = trend.points.first { it.label == "10月" }
            assertEquals(1_000_00L, sep.net, "改市值之前不受影响")
            assertEquals(6_000_00L, oct.net, "改市值后净资产增加 5000")
        }

    @Test
    fun currentMonthOnlyCountsUpToToday() =
        kotlinx.coroutines.runBlocking {
            // 今天之后（同月）的流水不该进入本月点位
            txnRepo.insert(txn("future", TxnType.INCOME, 900_00, at(2026, 11, 20)))
            val trend = trendUseCase().current(TrendPeriod.MONTH, today)
            assertEquals(1_000_00L, trend.points.last().net, "未来日期的流水不计入「至今」")
        }

    @Test
    fun leapYearFebruaryEndsCorrectly() {
        // 2024 是闰年：2 月末取 29 日，跨年回算不能把 2/29 的流水漏掉
        kotlinx.coroutines.runBlocking {
            txnRepo.insert(txn("t1", TxnType.EXPENSE, 100_00, at(2024, 2, 29)))
            val trend = trendUseCase().current(TrendPeriod.MONTH, LocalDate.of(2024, 3, 15))
            val feb = trend.points.first { it.label == "2月" }
            assertEquals(900_00L, feb.net, "闰日当天的流水必须算进 2 月末")
        }
    }

    // ---------- 变化记录 ----------

    @Test
    fun changesIncludeIncomeExpenseAndNetAfter() =
        kotlinx.coroutines.runBlocking {
            txnRepo.insert(txn("t1", TxnType.EXPENSE, 200_00, at(2026, 9, 10), note = "午饭", categoryId = "c_food"))
            txnRepo.insert(txn("t2", TxnType.INCOME, 500_00, at(2026, 10, 5)))

            val rows = changesUseCase().current()
            assertEquals(2, rows.size)
            // 倒序：最新的收入在前
            assertEquals(AssetChangeKind.INCOME, rows.first().kind)
            assertEquals(500_00L, rows.first().delta)
            assertEquals(1_300_00L, rows.first().netAfter, "变动后净资产 1000 − 200 + 500")
            val expense = rows.last()
            assertEquals(AssetChangeKind.EXPENSE, expense.kind)
            assertEquals(-200_00L, expense.delta)
            assertEquals(800_00L, expense.netAfter)
            assertTrue(expense.title.contains("餐饮"), "描述里要带分类名")
            assertEquals("t1", expense.txnId, "带上流水 id 才能点击跳转")
        }

    @Test
    fun transferExcludedFromChanges() =
        kotlinx.coroutines.runBlocking {
            db.accountDao().insertAll(listOf(account("sav", AccountType.SAVINGS)))
            txnRepo.insert(
                Txn(
                    id = "tr1",
                    type = TxnType.TRANSFER,
                    amount = 300_00,
                    categoryId = null,
                    accountId = "cash",
                    toAccountId = "sav",
                    dateTime = at(2026, 10, 5),
                    dateKey = 20261005,
                    createdAt = at(2026, 10, 5),
                    updatedAt = at(2026, 10, 5),
                ),
            )
            assertTrue(changesUseCase().current().isEmpty(), "PRD 6.4：转账不进变化记录")
        }

    @Test
    fun refundCountsAsIncrease() =
        kotlinx.coroutines.runBlocking {
            val spendAt = at(2026, 9, 10)
            txnRepo.insert(txn("t1", TxnType.EXPENSE, 200_00, spendAt, categoryId = "c_food"))
            txnRepo.insert(
                txn("t2", TxnType.INCOME, 80_00, at(2026, 9, 12), refundOf = "t1", categoryId = "c_food"),
            )
            val rows = changesUseCase().current()
            val refund = rows.first { it.kind == AssetChangeKind.REFUND }
            assertEquals(80_00L, refund.delta)
            assertTrue(refund.title.startsWith("退款"))
            assertEquals(880_00L, refund.netAfter)
        }

    @Test
    fun reimbursementCountsAsIncrease() =
        kotlinx.coroutines.runBlocking {
            txnRepo.insert(txn("t1", TxnType.EXPENSE, 200_00, at(2026, 9, 10), categoryId = "c_food"))
            txnRepo.insert(txn("t2", TxnType.INCOME, 200_00, at(2026, 9, 20), parentTxnId = "t1"))
            val rows = changesUseCase().current()
            val reimburse = rows.first { it.kind == AssetChangeKind.REIMBURSE }
            assertEquals(200_00L, reimburse.delta)
            assertEquals(1_000_00L, reimburse.netAfter, "报销后回到原值")
        }

    @Test
    fun deletedTxnAppearsAsNegative() =
        kotlinx.coroutines.runBlocking {
            val deleteAt = at(2026, 10, 1)
            txnRepo.insert(txn("t1", TxnType.INCOME, 300_00, at(2026, 9, 10)))
            txnRepo.softDelete("t1", deleteAt)

            val rows = changesUseCase().current()
            val deleted = rows.first { it.kind == AssetChangeKind.DELETED }
            assertEquals(-300_00L, deleted.delta, "删掉一笔收入，净资产反向减少")
            assertEquals(deleteAt, deleted.at, "时间取删除时刻")
            assertTrue(deleted.title.startsWith("删除"))
        }

    @Test
    fun marketValueEventInChanges() =
        kotlinx.coroutines.runBlocking {
            db.accountDao().insertAll(listOf(account("inv", AccountType.INVEST)))
            accountRepo.updateMarketValue("inv", 5_000_00, at(2026, 10, 20))

            val rows = changesUseCase().current()
            val event = rows.first { it.kind == AssetChangeKind.MARKET_VALUE }
            assertEquals(5_000_00L, event.delta)
            assertEquals(6_000_00L, event.netAfter)
            assertTrue(event.title.contains("调整市值"))
            assertTrue(event.title.contains("inv"), "事件标题带账户名")
            assertEquals(5_000_00L, event.amountAfter, "同时保留「调整到的新市值」供展示")
            assertEquals(null, event.txnId, "元事件没有对应流水，不可跳转")
        }

    @Test
    fun changesSortedByTimeDesc() =
        kotlinx.coroutines.runBlocking {
            txnRepo.insert(txn("t1", TxnType.EXPENSE, 100_00, at(2026, 7, 1)))
            txnRepo.insert(txn("t2", TxnType.EXPENSE, 200_00, at(2026, 8, 1)))
            txnRepo.insert(txn("t3", TxnType.EXPENSE, 300_00, at(2026, 9, 1)))
            val rows = changesUseCase().current()
            assertEquals(listOf("t3", "t2", "t1"), rows.map { it.txnId })
        }

    @Test
    fun limitKeepsNewest() =
        kotlinx.coroutines.runBlocking {
            txnRepo.insert(txn("t1", TxnType.EXPENSE, 100_00, at(2026, 7, 1)))
            txnRepo.insert(txn("t2", TxnType.EXPENSE, 200_00, at(2026, 8, 1)))
            val rows = changesUseCase().current(limit = 1)
            assertEquals(1, rows.size)
            assertEquals("t2", rows.first().txnId)
        }

    @Test
    fun breakdownMatchesAccounts() =
        kotlinx.coroutines.runBlocking {
            val assets = GetAssetBreakdownUseCase(accountRepo).current()
            assertEquals(1_000_00L, assets.total, "初始余额 1000 全部是现金")
            val liability = GetLiabilityBreakdownUseCase(accountRepo).current()
            assertEquals(0L, liability.total)
        }
}
