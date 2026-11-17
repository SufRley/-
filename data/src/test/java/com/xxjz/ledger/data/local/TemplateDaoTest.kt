package com.xxjz.ledger.data.local

import com.xxjz.ledger.data.local.entity.TemplateEntity
import com.xxjz.ledger.domain.model.TxnType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** M2 模板持久化：写入、排序、修改、删除（AC-13）。 */
@RunWith(RobolectricTestRunner::class)
class TemplateDaoTest {
    private lateinit var db: com.xxjz.ledger.data.local.db.AppDatabase

    @Before
    fun setUp() {
        db = TestDatabaseFactory.create()
    }

    @After
    fun tearDown() {
        if (this::db.isInitialized) db.close()
    }

    private fun template(
        id: String,
        name: String,
        order: Int,
        amount: Long = 1000,
    ) = TemplateEntity(
        id = id,
        name = name,
        type = TxnType.EXPENSE.name,
        amount = amount,
        categoryId = "exp_0_0",
        accountId = "acc_default_cash",
        toAccountId = null,
        note = "",
        sortOrder = order,
    )

    @Test
    fun `按排序值升序返回模板`() =
        runTest {
            db.templateDao().upsert(template("b", "地铁", 2))
            db.templateDao().upsert(template("a", "早餐", 1))
            val list = db.templateDao().observeAll().first()
            assertEquals(listOf("早餐", "地铁"), list.map { it.name })
        }

    @Test
    fun `同名主键写入即覆盖并可改金额`() =
        runTest {
            db.templateDao().upsert(template("t1", "早餐", 1, amount = 500))
            db.templateDao().upsert(template("t1", "早餐", 1, amount = 800))
            val list = db.templateDao().observeAll().first()
            assertEquals(1, list.size)
            assertEquals(800L, list.first().amount)
        }

    @Test
    fun `按id读取与删除`() =
        runTest {
            db.templateDao().upsert(template("t1", "早餐", 1))
            assertEquals("早餐", db.templateDao().byId("t1")?.name)
            db.templateDao().deleteById("t1")
            assertNull(db.templateDao().byId("t1"))
        }

    @Test
    fun `修改排序值后顺序变化`() =
        runTest {
            db.templateDao().upsert(template("a", "早餐", 1))
            db.templateDao().upsert(template("b", "地铁", 2))
            db.templateDao().setSortOrder("a", 9)
            val list = db.templateDao().observeAll().first()
            assertEquals(listOf("地铁", "早餐"), list.map { it.name })
        }
}
