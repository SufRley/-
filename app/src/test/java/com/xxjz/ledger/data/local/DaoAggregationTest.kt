package com.xxjz.ledger.data.local

import com.xxjz.ledger.data.local.dao.MetaEntity
import com.xxjz.ledger.data.local.db.AppDatabase
import com.xxjz.ledger.data.local.entity.AccountEntity
import com.xxjz.ledger.data.local.entity.CategoryEntity
import com.xxjz.ledger.data.local.entity.DebtEntity
import com.xxjz.ledger.data.local.entity.TagEntity
import com.xxjz.ledger.data.local.entity.TxnEntity
import com.xxjz.ledger.data.local.entity.TxnTagEntity
import com.xxjz.ledger.data.local.seed.SeedData
import com.xxjz.ledger.data.local.seed.Seeder
import com.xxjz.ledger.domain.model.AccountType
import com.xxjz.ledger.domain.model.TxnType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** M1 集成测试：Room in-memory + 真实 SQL 聚合。 */
@RunWith(RobolectricTestRunner::class)
class DaoAggregationTest {
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = TestDatabaseFactory.create()
    }

    @After
    fun tearDown() {
        if (this::db.isInitialized) db.close()
    }

    @Suppress("LongParameterList") // 测试造数工厂，字段一一对应实体，拆开反而更难读
    private fun txn(
        id: String,
        type: TxnType,
        amount: Long,
        categoryId: String? = "exp_0_0",
        accountId: String = "acc",
        dateTime: Long = 1_700_000_000_000L,
        dateKey: Int = 20231114,
        excluded: Boolean = false,
        refundOf: String? = null,
        toAccountId: String? = null,
        fee: Long = 0,
        deletedAt: Long? = null,
        debtId: String? = null,
        pending: Boolean = false,
    ) = TxnEntity(
        id = id,
        type = type.name,
        amount = amount,
        categoryId = categoryId,
        accountId = accountId,
        toAccountId = toAccountId,
        fee = fee,
        dateTime = dateTime,
        dateKey = dateKey,
        note = "",
        memo = "",
        merchantId = null,
        merchantName = null,
        currency = "CNY",
        ledgerId = null,
        excludedFromStats = excluded,
        pendingReimbursement = pending,
        reimbursedAmount = 0,
        reimbursedAt = null,
        refundOfTxnId = refundOf,
        parentTxnId = null,
        debtId = debtId,
        deletedAt = deletedAt,
        createdAt = dateTime,
        updatedAt = dateTime,
    )

    private fun account(
        id: String,
        initial: Long,
        order: Int = 0,
    ) = AccountEntity(
        id = id,
        name = id,
        type = AccountType.FUND.name,
        initialBalance = initial,
        icon = "",
        color = "",
        cardLimit = null,
        billDay = null,
        repayDay = null,
        hidden = false,
        sortOrder = order,
    )

    @Test
    fun `按月汇总排除不计入收支与转账`() =
        runTest {
            db.txnDao().insertAll(
                listOf(
                    txn("t1", TxnType.EXPENSE, 1000, dateKey = 20231101),
                    txn("t2", TxnType.EXPENSE, 2000, dateKey = 20231102),
                    txn("t3", TxnType.INCOME, 5000, dateKey = 20231103),
                    txn("t4", TxnType.EXPENSE, 9999, dateKey = 20231104, excluded = true),
                    txn("t5", TxnType.TRANSFER, 8888, dateKey = 20231105),
                    txn("t6", TxnType.EXPENSE, 7777, dateKey = 20231106, deletedAt = 1L),
                ),
            )
            val buckets = db.txnDao().sumByType(0, Long.MAX_VALUE)
            val expense = buckets.first { it.key == TxnType.EXPENSE.name }
            val income = buckets.first { it.key == TxnType.INCOME.name }
            assertEquals(3000L, expense.total)
            assertEquals(2, expense.cnt)
            assertEquals(5000L, income.total)
            assertEquals(1, income.cnt)
            assertNull(buckets.firstOrNull { it.key == TxnType.TRANSFER.name })
        }

    @Test
    fun `按分类分组并按金额倒序`() =
        runTest {
            db.txnDao().insertAll(
                listOf(
                    txn("c1", TxnType.EXPENSE, 1000, categoryId = "food"),
                    txn("c2", TxnType.EXPENSE, 3000, categoryId = "food"),
                    txn("c3", TxnType.EXPENSE, 500, categoryId = "traffic"),
                    txn("c4", TxnType.INCOME, 9000, categoryId = "salary"),
                ),
            )
            val sums = db.txnDao().sumByCategory(TxnType.EXPENSE.name, 0, Long.MAX_VALUE)
            assertEquals(2, sums.size)
            assertEquals("food", sums[0].key)
            assertEquals(4000L, sums[0].total)
            assertEquals(2, sums[0].cnt)
            assertEquals("traffic", sums[1].key)
            assertEquals(500L, sums[1].total)
        }

    @Test
    fun `按天分组用于日历圆点`() =
        runTest {
            db.txnDao().insertAll(
                listOf(
                    txn("d1", TxnType.EXPENSE, 100, dateKey = 20231101),
                    txn("d2", TxnType.EXPENSE, 200, dateKey = 20231101),
                    txn("d3", TxnType.EXPENSE, 300, dateKey = 20231102),
                    txn("d4", TxnType.EXPENSE, 400, dateKey = 20231201),
                ),
            )
            val days = db.txnDao().sumByDay(TxnType.EXPENSE.name, 20231101, 20231130)
            assertEquals(2, days.size)
            assertEquals("20231101", days[0].key)
            assertEquals(300L, days[0].total)
            assertEquals("20231102", days[1].key)
        }

    @Test
    fun `退款累计不超原额的数据基础`() =
        runTest {
            db.txnDao().insertAll(
                listOf(
                    txn("orig", TxnType.EXPENSE, 10000),
                    txn("r1", TxnType.INCOME, 3000, refundOf = "orig"),
                    txn("r2", TxnType.INCOME, 2000, refundOf = "orig"),
                    txn("r3", TxnType.INCOME, 1000, refundOf = "orig", deletedAt = 5L),
                ),
            )
            assertEquals(5000L, db.txnDao().refundedTotal("orig"))
            assertEquals(0L, db.txnDao().refundedTotal("none"))
        }

    @Test
    fun `账户余额一条 SQL 算完`() =
        runTest {
            db.accountDao().insertAll(listOf(account("a1", 10000, 0), account("a2", 0, 1)))
            db.txnDao().insertAll(
                listOf(
                    txn("b1", TxnType.EXPENSE, 1500, accountId = "a1"),
                    txn("b2", TxnType.INCOME, 3000, accountId = "a1"),
                    txn("b3", TxnType.TRANSFER, 2000, accountId = "a1", toAccountId = "a2", fee = 100),
                    txn("b4", TxnType.EXPENSE, 500, accountId = "a1", deletedAt = 1L),
                ),
            )
            val balances = db.accountDao().observeBalances().first().associate { it.accountId to it.balance }
            assertEquals(9400L, balances["a1"])
            assertEquals(2000L, balances["a2"])
            assertEquals(9400L, db.accountDao().balanceOf("a1")?.balance)
        }

    @Test
    fun `预置数据幂等写入且分类可读`() =
        runTest {
            assertTrue(Seeder.seedIfNeeded(db))
            assertFalse("重复调用不应重复插入", Seeder.seedIfNeeded(db))

            val all = db.categoryDao().listAll()
            assertTrue("应有完整分类树，实际 ${all.size}", all.size > 40)
            val majors = all.filter { it.parentId == null && it.type == TxnType.EXPENSE.name }
            assertEquals(9, majors.size)
            assertTrue(
                majors.map { it.name }.containsAll(listOf("餐饮", "交通", "购物", "居住", "娱乐", "医疗", "教育", "人情", "其他")),
            )
            val foodChildren = db.categoryDao().childrenOf("exp_0")
            assertEquals(7, foodChildren.size)
            assertEquals("早餐", foodChildren.first().name)

            assertNotNull(db.categoryDao().byId(SeedData.REFUND_CATEGORY_ID))
            assertEquals(1, db.accountDao().count())
            assertNotNull(db.ledgerDao().defaultLedger())
            assertEquals("1", db.metaDao().get(AppDatabase.KEY_SEED))
        }

    @Test
    fun `软删除与恢复与不计入收支开关`() =
        runTest {
            db.txnDao().insert(txn("s1", TxnType.EXPENSE, 1000))
            db.txnDao().softDelete("s1", 10L)
            assertEquals(0, db.txnDao().observeInRange(0, Long.MAX_VALUE).first().size)
            assertEquals(1, db.txnDao().observeDeleted().first().size)

            db.txnDao().restore("s1", 20L)
            assertEquals(1, db.txnDao().observeInRange(0, Long.MAX_VALUE).first().size)

            db.txnDao().setExcluded("s1", true, 30L)
            assertNull(db.txnDao().sumByType(0, Long.MAX_VALUE).firstOrNull())
            db.txnDao().setExcluded("s1", false, 40L)
            assertEquals(1000L, db.txnDao().sumByType(0, Long.MAX_VALUE).first().total)
        }

    @Test
    fun `待报销列表只含未删除支出`() =
        runTest {
            db.txnDao().insert(txn("p1", TxnType.EXPENSE, 1000, pending = true))
            db.txnDao().insert(txn("p2", TxnType.EXPENSE, 2000))
            val pending = db.txnDao().listPendingReimbursement()
            assertEquals(1, pending.size)
            assertEquals("p1", pending.first().id)
        }

    @Test
    fun `借贷剩余等于本金减已还`() =
        runTest {
            db.debtDao().insert(
                DebtEntity(
                    id = "debt1",
                    direction = "LEND",
                    counterparty = "小王",
                    principal = 50000,
                    accountId = "a1",
                    startAt = 0,
                    dueAt = null,
                    note = "",
                    settled = false,
                ),
            )
            db.txnDao().insert(txn("repay1", TxnType.TRANSFER, 20000, categoryId = null, debtId = "debt1"))
            assertEquals(20000L, db.debtDao().repaidTotal("debt1"))
            val remaining = db.debtDao().observeRemaining().first().first { it.key == "debt1" }
            assertEquals(30000L, remaining.total)
        }

    @Test
    fun `标签关联与使用次数`() =
        runTest {
            db.tagDao().insert(TagEntity("tag1", "出差", "", 0))
            db.tagDao().linkTags(listOf(TxnTagEntity("tx", "tag1")))
            assertEquals(listOf("tag1"), db.tagDao().tagIdsOf("tx"))
            db.tagDao().increaseUsage("tag1")
            assertEquals(1, db.tagDao().listAll().first().usageCount)
            db.tagDao().clearTags("tx")
            assertTrue(db.tagDao().tagIdsOf("tx").isEmpty())
        }

    @Test
    fun `元数据键值可读写`() =
        runTest {
            assertNull(db.metaDao().get("k"))
            db.metaDao().put(MetaEntity("k", "v1"))
            assertEquals("v1", db.metaDao().get("k"))
            db.metaDao().put(MetaEntity("k", "v2"))
            assertEquals("v2", db.metaDao().get("k"))
        }

    @Test
    fun `分类使用次数聚合`() =
        runTest {
            db.categoryDao().insertAll(
                listOf(
                    CategoryEntity("food", "餐饮", null, TxnType.EXPENSE.name, "", "", 0, false, false, 0),
                    CategoryEntity("sub", "早餐", "food", TxnType.EXPENSE.name, "", "", 1, false, false, 0),
                ),
            )
            db.txnDao().insertAll(
                listOf(
                    txn("u1", TxnType.EXPENSE, 100, categoryId = "sub"),
                    txn("u2", TxnType.EXPENSE, 100, categoryId = "sub"),
                ),
            )
            val usage = db.txnDao().observeCategoryUsage().first().associate { it.key to it.cnt }
            assertEquals(2, usage["sub"])
            assertEquals(2, db.txnDao().usageCountOf("sub"))
        }
}
