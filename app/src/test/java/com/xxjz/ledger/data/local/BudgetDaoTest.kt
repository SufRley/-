package com.xxjz.ledger.data.local

import com.xxjz.ledger.data.local.entity.BudgetEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** M6 预算持久化：按月继承、软删除、按 (id, monthKey) 分版本（AC-49、AC-50）。 */
@RunWith(RobolectricTestRunner::class)
class BudgetDaoTest {
    private lateinit var db: com.xxjz.ledger.data.local.db.AppDatabase

    @Before
    fun setUp() {
        db = TestDatabaseFactory.create()
    }

    @After
    fun tearDown() {
        if (this::db.isInitialized) db.close()
    }

    private fun budget(
        id: String,
        monthKey: Int,
        amount: Long = 100_000,
        deletedAt: Long? = null,
        enabled: Boolean = true,
        order: Int = 0,
    ) = BudgetEntity(
        id = id,
        monthKey = monthKey,
        name = id,
        period = "MONTH",
        scope = "TOTAL",
        categoryIds = "",
        amount = amount,
        enabled = enabled,
        deletedAt = deletedAt,
        sortOrder = order,
    )

    @Test
    fun monthRowsReturnsOnlyThatMonth() =
        runTest {
            val dao = db.budgetDao()
            dao.insert(budget("a", 202610))
            dao.insert(budget("b", 202611))

            assertEquals(listOf("b"), dao.monthRows(202611).map { it.id })
            assertEquals(2, dao.monthRows(202610).size + dao.monthRows(202611).size)
        }

    @Test
    fun latestBeforePicksNearestMonth() =
        runTest {
            val dao = db.budgetDao()
            dao.insert(budget("old", 202608))
            dao.insert(budget("near", 202610))
            dao.insert(budget("future", 202612))

            val latest = dao.latestBefore(202611)
            assertEquals(listOf("near"), latest.map { it.id })
            // 没有更早的月份时返回空
            assertTrue(dao.latestBefore(202608).isEmpty())
        }

    @Test
    fun softDeleteKeepsRowButExcludedFromEffective() =
        runTest {
            val dao = db.budgetDao()
            dao.insert(budget("a", 202611))

            dao.softDelete("a", 202611, at = 123L)

            val rows = dao.monthRows(202611)
            assertEquals(1, rows.size)
            assertEquals(123L, rows.single().deletedAt)
            // 该月仍算「配置过」，不会因为没有有效行而回退到上月
            assertTrue(dao.monthRows(202611).isNotEmpty())
        }

    @Test
    fun restoreClearsDeletedAt() =
        runTest {
            val dao = db.budgetDao()
            dao.insert(budget("a", 202611, deletedAt = 5L))
            dao.restore("a", 202611)
            assertNull(dao.monthRows(202611).single().deletedAt)
        }

    @Test
    fun sameIdAcrossMonthsAreIndependent() =
        runTest {
            val dao = db.budgetDao()
            dao.insert(budget("a", 202610, amount = 100_000))
            dao.insert(budget("a", 202611, amount = 300_000))

            dao.setEnabled("a", 202611, enabled = false)

            assertEquals(300_000L, dao.monthRows(202611).single().amount)
            assertEquals(false, dao.monthRows(202611).single().enabled)
            assertEquals(true, dao.monthRows(202610).single().enabled)
            // 上月版本金额不受影响
            assertEquals(100_000L, dao.monthRows(202610).single().amount)
        }

    @Test
    fun observeMonthRowsEmitsOnInsert() =
        runTest {
            val dao = db.budgetDao()
            assertTrue(dao.observeMonthRows(202611).first().isEmpty())

            dao.insert(budget("a", 202611))
            val rows = dao.observeMonthRows(202611).first()
            assertEquals(1, rows.size)
            assertNotNull(rows.single().id)
        }
}
