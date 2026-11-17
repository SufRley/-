package com.xxjz.ledger.data.local

import com.xxjz.ledger.core.common.DateUtils
import com.xxjz.ledger.data.local.entity.TxnEntity
import com.xxjz.ledger.domain.model.TxnType
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

/** M4 关键词检索 SQL 集成测试（AC-28 / AC-29 / AC-30）。 */
@RunWith(RobolectricTestRunner::class)
class TxnSearchDaoTest {
    private lateinit var db: com.xxjz.ledger.data.local.db.AppDatabase

    private val mar1: Long = DateUtils.toMillis(LocalDate.of(2025, 3, 1), LocalTime.of(9, 0))
    private val mar15: Long = DateUtils.toMillis(LocalDate.of(2025, 3, 15), LocalTime.of(12, 0))
    private val mar31: Long = DateUtils.toMillis(LocalDate.of(2025, 3, 31), LocalTime.of(23, 0))
    private val apr2: Long = DateUtils.toMillis(LocalDate.of(2025, 4, 2), LocalTime.of(8, 0))

    @Before
    fun setUp() {
        db = TestDatabaseFactory.create()
    }

    @After
    fun tearDown() {
        if (this::db.isInitialized) db.close()
    }

    private fun txn(
        id: String,
        amount: Long,
        at: Long,
        note: String = "",
        type: TxnType = TxnType.EXPENSE,
        merchant: String? = null,
        deleted: Boolean = false,
    ) = TxnEntity(
        id = id,
        type = type.name,
        amount = amount,
        categoryId = "exp_food",
        accountId = "acc_cash",
        toAccountId = null,
        fee = 0,
        dateTime = at,
        dateKey = DateUtils.dateKeyOf(at),
        note = note,
        memo = "",
        merchantId = null,
        merchantName = merchant,
        currency = "CNY",
        ledgerId = null,
        excludedFromStats = false,
        pendingReimbursement = false,
        reimbursedAmount = 0,
        reimbursedAt = null,
        refundOfTxnId = null,
        parentTxnId = null,
        debtId = null,
        deletedAt = if (deleted) at else null,
        createdAt = at,
        updatedAt = at,
    )

    private suspend fun seed() {
        db.txnDao().insert(txn("t1", 2800, mar1, note = "午饭 拉面"))
        db.txnDao().insert(txn("t2", 1200, mar15, note = "地铁", merchant = "地铁公司"))
        db.txnDao().insert(txn("t3", 50000, mar31, note = "房租"))
        db.txnDao().insert(txn("t4", 300, apr2, note = "早饭 包子"))
        db.txnDao().insert(txn("t5", 9999, mar15, note = "午饭 便当", type = TxnType.INCOME).copy(createdAt = mar15 + 1))
        db.txnDao().insert(txn("t6", 777, mar15, note = "午饭 已删", deleted = true))
    }

    private suspend fun search(
        keyword: String = "",
        type: String = "",
        min: Long = 0,
        max: Long = Long.MAX_VALUE,
        start: Long = 0,
        end: Long = Long.MAX_VALUE,
    ) = db.txnDao().search(keyword, type, min, max, start, end).map { it.id }

    @Test
    fun `空条件返回全部未删除记录`() =
        runTest {
            seed()
            val ids = search()
            assertEquals(setOf("t1", "t2", "t3", "t4", "t5"), ids.toSet())
            assertEquals(5, ids.size)
        }

    @Test
    fun `关键词命中备注并排除已删除`() =
        runTest {
            seed()
            // t6 备注也含「午饭」但已软删
            assertEquals(setOf("t1", "t5"), search(keyword = "午饭").toSet())
        }

    @Test
    fun `关键词命中商户名`() =
        runTest {
            seed()
            assertEquals(listOf("t2"), search(keyword = "地铁公司"))
        }

    @Test
    fun `类型过滤只返回支出`() =
        runTest {
            seed()
            val ids = search(type = TxnType.EXPENSE.name)
            assertTrue("收入不应出现在支出结果", "t5" !in ids)
            assertEquals(4, ids.size)
        }

    @Test
    fun `金额区间为闭区间且以分为单位`() =
        runTest {
            seed()
            // 12 元 ~ 28 元 → 1200..2800，含边界
            assertEquals(setOf("t1", "t2"), search(min = 1200, max = 2800).toSet())
            // 只给下限 2800 → t1(2800)/t3/t5 都满足，t4(300) 不满足
            assertEquals(setOf("t1", "t3", "t5"), search(min = 2800).toSet())
            // 只给上限 1200
            assertEquals(setOf("t2", "t4"), search(max = 1200).toSet())
        }

    @Test
    fun `时间区间只取本月`() =
        runTest {
            seed()
            val span = DateUtils.monthRange(2025, 3)
            val ids = search(start = span.first, end = span.last)
            assertEquals(setOf("t1", "t2", "t3", "t5"), ids.toSet())
            assertTrue("4 月记录不应出现", "t4" !in ids)
        }

    @Test
    fun `多条件组合同时生效`() =
        runTest {
            seed()
            val span = DateUtils.monthRange(2025, 3)
            val ids =
                search(
                    keyword = "午饭",
                    type = TxnType.EXPENSE.name,
                    min = 1000,
                    max = 20000,
                    start = span.first,
                    end = span.last,
                )
            assertEquals(listOf("t1"), ids)
        }

    @Test
    fun `结果按时间倒序`() =
        runTest {
            seed()
            val ids = search(start = DateUtils.monthRange(2025, 3).first, end = DateUtils.monthRange(2025, 3).last)
            assertEquals(listOf("t3", "t5", "t2", "t1"), ids)
        }

    @Test
    fun `无匹配返回空列表`() =
        runTest {
            seed()
            assertTrue(search(keyword = "不存在的关键词").isEmpty())
        }
}
