package com.xxjz.ledger.data.local

import com.xxjz.ledger.core.common.DateUtils
import com.xxjz.ledger.data.local.db.AppDatabase
import com.xxjz.ledger.data.local.entity.TxnEntity
import com.xxjz.ledger.domain.model.Category
import com.xxjz.ledger.domain.model.Txn
import com.xxjz.ledger.domain.model.TxnType
import com.xxjz.ledger.feature.ledger.LedgerGrouper
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.time.LocalTime

/**
 * M10-T4 性能压测：5000 笔账目下的聚合与分组耗时。
 *
 * 为什么用「上限断言」而不是打印耗时：
 * 打印的数字没人会在 CI 里看，回归时也不会有人记得基线是多少。
 * 这里给的是**宽松但明确**的上限（本机实测通常只用到 1/3 左右），
 * 一旦有人写出 N+1 查询或把聚合挪到主线程，就会立刻超阈值报错。
 *
 * 用例本身也顺带验证正确性：5000 笔的合计必须与手工计算一致，
 * 否则「跑得快但算错」会被当成通过。
 */
@RunWith(RobolectricTestRunner::class)
class PerformanceTest {
    private lateinit var db: AppDatabase

    /** 压测规模：5000 笔，跨 24 个月，覆盖 8 个分类、3 个账户。 */
    private val count = 5000

    @Before
    fun setUp() {
        db = TestDatabaseFactory.create()
    }

    @After
    fun tearDown() {
        if (this::db.isInitialized) db.close()
    }

    private fun seedTxns() {
        val start = DateUtils.toMillis(LocalDate.of(2025, 1, 1), LocalTime.of(9, 0))
        val dayMillis = 24 * 3600 * 1000L
        val rows =
            (0 until count).map { i ->
                val at = start + i * dayMillis / 7 // 每天约 7 笔，铺满 24 个月
                val expense = i % 5 != 0
                TxnEntity(
                    id = "t$i",
                    type = if (expense) TxnType.EXPENSE.name else TxnType.INCOME.name,
                    amount = 100L + (i % 900) * 10,
                    categoryId = if (expense) "exp_${i % 8}" else "inc_${i % 3}",
                    accountId = "acc_${i % 3}",
                    toAccountId = null,
                    fee = 0,
                    dateTime = at,
                    dateKey = DateUtils.dateKeyOf(at),
                    note = "第 $i 笔",
                    memo = "",
                    merchantId = null,
                    merchantName = if (i % 4 == 0) "商家${i % 20}" else null,
                    currency = "CNY",
                    ledgerId = null,
                    excludedFromStats = false,
                    pendingReimbursement = false,
                    reimbursedAmount = 0,
                    reimbursedAt = null,
                    refundOfTxnId = null,
                    parentTxnId = null,
                    debtId = null,
                    deletedAt = null,
                    createdAt = at,
                    updatedAt = at,
                )
            }
        runTest { db.txnDao().insertAll(rows) }
    }

    private fun millisOf(
        year: Int,
        month: Int,
    ): Pair<Long, Long> {
        val range = DateUtils.monthRange(year, month)
        return range.first to range.last
    }

    @Test
    fun `5000 笔按月汇总在 300ms 内完成且结果正确`() =
        runTest {
            seedTxns()
            val (start, end) = millisOf(2025, 3)
            val warmup = db.txnDao().sumByType(start, end) // 预热：首次查询含 SQLite 准备开销
            assertTrue(warmup.isNotEmpty())

            val begin = System.nanoTime()
            val buckets = db.txnDao().sumByType(start, end)
            val costMs = (System.nanoTime() - begin) / 1_000_000
            println("sumByType(5000) = ${costMs}ms")

            assertTrue("按月汇总耗时 ${costMs}ms，超过 300ms 上限", costMs < 300)
            // 合计必须等于在内存里重算的结果，防止「快但错」
            val expected =
                db
                    .txnDao()
                    .listInRange(start, end)
                    .filter { it.excludedFromStats.not() }
                    .groupBy { it.type }
                    .mapValues { (_, list) -> list.sumOf { it.amount } }
            buckets.forEach { bucket ->
                assertEquals("类型 ${bucket.key} 的合计不对", expected[bucket.key], bucket.total)
            }
        }

    @Test
    fun `5000 笔按分类聚合在 300ms 内完成`() =
        runTest {
            seedTxns()
            val (start, end) = millisOf(2025, 6)
            db.txnDao().sumByCategory(TxnType.EXPENSE.name, start, end) // 预热

            val begin = System.nanoTime()
            val rows = db.txnDao().sumByCategory(TxnType.EXPENSE.name, start, end)
            val costMs = (System.nanoTime() - begin) / 1_000_000
            println("sumByCategory(5000) = ${costMs}ms，分组数=${rows.size}")

            assertTrue("按分类聚合耗时 ${costMs}ms，超过 300ms 上限", costMs < 300)
            assertTrue(rows.isNotEmpty())
            // 分类聚合的 key 必须是真实分类 id，不能出现 null（那意味着有流水没归类）
            assertTrue("出现了未归类的支出", rows.none { it.key == null })
        }

    @Test
    fun `5000 笔取一整个月列表在 400ms 内完成`() =
        runTest {
            seedTxns()
            val (start, end) = millisOf(2025, 9)
            db.txnDao().listInRange(start, end)

            val begin = System.nanoTime()
            val list = db.txnDao().listInRange(start, end)
            val costMs = (System.nanoTime() - begin) / 1_000_000
            println("listInRange 单月 ${list.size} 条 = ${costMs}ms")

            assertTrue("单月列表查询 ${costMs}ms，超过 400ms 上限", costMs < 400)
            assertTrue(list.size in 100..3000)
        }

    @Test
    fun `5000 笔资产报表回放在 400ms 内完成`() =
        runTest {
            seedTxns()
            val rows = db.txnDao().listAllIncludingDeleted().map { it.toModelForPerf() }
            val accounts =
                (0 until 3).map { i ->
                    com.xxjz.ledger.domain.model.Account(
                        id = "acc_$i",
                        name = "账户$i",
                        type = com.xxjz.ledger.domain.model.AccountType.SAVINGS,
                        initialBalance = 100_000L,
                    )
                }
            // 预热：首次调用含类加载与 JIT
            com.xxjz.ledger.domain.usecase.NetWorthReplay.replay(0, rows)

            val begin = System.nanoTime()
            val points =
                com.xxjz.ledger.domain.usecase.NetWorthReplay.replay(
                    com.xxjz.ledger.domain.usecase.NetWorthReplay.initialNet(accounts),
                    rows,
                    emptyList(),
                )
            val costMs = (System.nanoTime() - begin) / 1_000_000
            println("NetWorthReplay(5000) = ${costMs}ms，步骤数=${points.size}")

            // PRD 八：净资产回算与变化记录需与现有基准同量级（聚合 ≤1ms / 列表 ≤8ms 之外的单次离线计算）
            assertTrue("净资产回放耗时 ${costMs}ms，超过 400ms 上限", costMs < 400)
            // 回放终点必须等于「初始 + 所有收支净额」，防止快但错
            val expected =
                300_000L +
                    rows.sumOf {
                        when (it.type) {
                            TxnType.EXPENSE -> -it.amount
                            TxnType.INCOME -> it.amount
                            else -> 0L
                        }
                    }
            assertEquals(expected, points.last().second)
        }

    /** 性能用例只关心金额与类型，字段映射从简。 */
    private fun TxnEntity.toModelForPerf(): Txn =
        Txn(
            id = id,
            type = TxnType.valueOf(type),
            amount = amount,
            categoryId = categoryId,
            accountId = accountId,
            toAccountId = toAccountId,
            fee = fee,
            dateTime = dateTime,
            dateKey = dateKey,
            note = note,
            refundOfTxnId = refundOfTxnId,
            parentTxnId = parentTxnId,
            deletedAt = deletedAt,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )

    @Test
    fun `5000 笔分组渲染在 200ms 内完成`() {
        val categories =
            (0 until 8).associate { i ->
                "exp_$i" to Category(id = "exp_$i", name = "分类$i", parentId = null, type = TxnType.EXPENSE)
            } +
                (0 until 3).associate { i ->
                    "inc_$i" to Category(id = "inc_$i", name = "收入$i", parentId = null, type = TxnType.INCOME)
                }
        val start = DateUtils.toMillis(LocalDate.of(2025, 1, 1), LocalTime.of(9, 0))
        val txns =
            (0 until count).map { i ->
                val at = start + i * 3_600_000L * 5
                Txn(
                    id = "t$i",
                    type = if (i % 5 != 0) TxnType.EXPENSE else TxnType.INCOME,
                    amount = 100L + i,
                    categoryId = if (i % 5 != 0) "exp_${i % 8}" else "inc_${i % 3}",
                    accountId = "acc",
                    dateTime = at,
                    dateKey = DateUtils.dateKeyOf(at),
                    createdAt = at,
                    updatedAt = at,
                )
            }

        LedgerGrouper.group(txns, categories) // 预热 JIT

        val begin = System.nanoTime()
        val groups = LedgerGrouper.group(txns, categories)
        val costMs = (System.nanoTime() - begin) / 1_000_000
        println("LedgerGrouper(5000) = ${costMs}ms，天数=${groups.size}")

        assertTrue("分组渲染耗时 ${costMs}ms，超过 200ms 上限", costMs < 200)
        // 分组不能丢数据：所有行的总数必须等于输入
        assertEquals(count, groups.sumOf { it.rows.size })
    }
}
