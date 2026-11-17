package com.xxjz.ledger.data.local

import com.xxjz.ledger.data.local.entity.DebtEntity
import com.xxjz.ledger.data.local.entity.RecurringRuleEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** M7 周期规则与借贷的持久化语义（AC-52、AC-57、AC-63、AC-65）。 */
@RunWith(RobolectricTestRunner::class)
class RecurringDaoTest {
    private lateinit var db: com.xxjz.ledger.data.local.db.AppDatabase

    @Before
    fun setUp() {
        db = TestDatabaseFactory.create()
    }

    @After
    fun tearDown() {
        if (this::db.isInitialized) db.close()
    }

    private fun rule(
        id: String,
        nextRunAt: Long,
        paused: Boolean = false,
        endAt: Long? = null,
    ) = RecurringRuleEntity(
        id = id,
        type = "EXPENSE",
        amount = 3500,
        categoryId = "exp_0_0",
        accountId = "acc-1",
        toAccountId = null,
        note = "房租",
        period = "MONTH",
        interval = 1,
        nextRunAt = nextRunAt,
        endAt = endAt,
        maxTimes = null,
        runTimes = 0,
        paused = paused,
    )

    @Test
    fun `到期查询排除暂停与已过结束日的规则`() =
        runTest {
            db.recurringDao().insert(rule("due", 1_000))
            db.recurringDao().insert(rule("future", 9_000))
            db.recurringDao().insert(rule("paused", 1_000, paused = true))
            db.recurringDao().insert(rule("ended", 1_000, endAt = 500))

            val due = db.recurringDao().dueRules(2_000)
            assertEquals(listOf("due"), due.map { it.id })
        }

    @Test
    fun `执行后推进游标，再次查询不再到期（幂等基础）`() =
        runTest {
            db.recurringDao().insert(rule("r1", 1_000))
            assertEquals(1, db.recurringDao().dueRules(2_000).size)

            db.recurringDao().advance("r1", nextRunAt = 50_000, runTimes = 1, paused = false)
            assertTrue(db.recurringDao().dueRules(2_000).isEmpty())

            val stored = db.recurringDao().byId("r1")!!
            assertEquals(50_000, stored.nextRunAt)
            assertEquals(1, stored.runTimes)
        }

    @Test
    fun `暂停与恢复写回下次执行时间`() =
        runTest {
            db.recurringDao().insert(rule("r1", 1_000))
            db.recurringDao().setPaused("r1", true, 1_000)
            assertTrue(db.recurringDao().byId("r1")!!.paused)
            assertTrue(db.recurringDao().dueRules(2_000).isEmpty())

            db.recurringDao().setPaused("r1", false, 77_000)
            val stored = db.recurringDao().byId("r1")!!
            assertFalse(stored.paused)
            assertEquals(77_000, stored.nextRunAt)
        }

    @Test
    fun `列表按下次执行时间正序`() =
        runTest {
            db.recurringDao().insert(rule("late", 30_000))
            db.recurringDao().insert(rule("soon", 10_000))
            assertEquals(listOf("soon", "late"), db.recurringDao().observeAll().first().map { it.id })
        }

    @Test
    fun `借贷坏账字段可持久化并默认 false`() =
        runTest {
            db.debtDao().insert(
                DebtEntity(
                    id = "d1",
                    direction = "LEND",
                    counterparty = "小王",
                    principal = 50_000,
                    accountId = "acc-1",
                    startAt = 0,
                    dueAt = null,
                    note = "",
                    settled = false,
                ),
            )
            assertFalse(db.debtDao().byId("d1")!!.writtenOff)

            val stored = db.debtDao().byId("d1")!!
            db.debtDao().upsert(stored.copy(writtenOff = true, settled = true))
            assertTrue(db.debtDao().byId("d1")!!.writtenOff)
            assertNotNull(db.debtDao().listAll().single())
        }
}
