package com.xxjz.ledger.data.local

import com.xxjz.ledger.data.local.db.MIGRATION_4_5
import com.xxjz.ledger.data.local.db.MIGRATION_5_6
import com.xxjz.ledger.data.local.db.MIGRATION_6_7
import com.xxjz.ledger.domain.model.AccountType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v4 → v5 迁移（v1.8.0 / PRD F1、D4）。
 *
 * 走 [TestDatabaseFactory] 的真实 Room 连接（和线上同一套 SQLite 引擎），在连接上手工建 v4 结构的
 * account 表、插老数据，再执行 [MIGRATION_4_5]，最后用同一连接断言——测的就是线上会跑的那段 SQL。
 *
 * 这里刻意用 JUnit4 而不是 JUnit5：`MigrationTestHelper` / `ApplicationProvider` 都依赖已注册的
 * instrumentation，只有 `RobolectricTestRunner` 的 JUnit4 生命周期会自动装好；换成 JUnit5 会报
 * 「No instrumentation registered」。功能一样，选能跑通的那条路。
 */
@RunWith(RobolectricTestRunner::class)
class MigrationV5Test {
    private lateinit var db: com.xxjz.ledger.data.local.db.AppDatabase

    /** v4 的 account 建表语句（与 `app/schemas/.../4.json` 一致）。 */
    private val createV4Account =
        """
        CREATE TABLE IF NOT EXISTS `account` (
            `id` TEXT NOT NULL,
            `name` TEXT NOT NULL,
            `type` TEXT NOT NULL,
            `initialBalance` INTEGER NOT NULL,
            `icon` TEXT NOT NULL,
            `color` TEXT NOT NULL,
            `cardLimit` INTEGER,
            `billDay` INTEGER,
            `repayDay` INTEGER,
            `hidden` INTEGER NOT NULL,
            `sortOrder` INTEGER NOT NULL,
            `deletedAt` INTEGER,
            PRIMARY KEY(`id`)
        )
        """.trimIndent()

    @Before
    fun setUp() {
        db = TestDatabaseFactory.create()
        // Room 建出来的表已经是 v5 结构，先清掉再按 v4 重建，模拟「老库」
        raw().execSQL("DROP TABLE IF EXISTS `account`")
        raw().execSQL(createV4Account)
    }

    @After
    fun tearDown() {
        if (this::db.isInitialized) db.close()
    }

    private fun raw() = db.openHelper.writableDatabase

    private fun seed(
        id: String,
        type: String,
        deletedAt: Long? = null,
    ) {
        val columns =
            "(`id`,`name`,`type`,`initialBalance`,`icon`,`color`,`cardLimit`,`billDay`,`repayDay`,`hidden`,`sortOrder`,`deletedAt`)"
        raw().execSQL(
            "INSERT INTO `account` $columns " +
                "VALUES ('$id','$id','$type',1000,'','',NULL,NULL,NULL,0,0,${deletedAt ?: "NULL"})",
        )
    }

    private fun typeOf(id: String): String =
        raw().query("SELECT `type` FROM `account` WHERE `id` = '$id'").use { c ->
            c.moveToFirst()
            c.getString(0)
        }

    @Test
    fun addsColumnsWithDefaults() {
        seed("a1", "FUND")

        MIGRATION_4_5.migrate(raw())

        raw().query("SELECT `costCents`,`marketValueCents`,`includeInNetAssets` FROM `account` WHERE `id`='a1'").use { c ->
            assertTrue("原始行必须还在", c.moveToFirst())
            assertEquals(0L, c.getLong(0))
            assertEquals(0L, c.getLong(1))
            assertEquals(1, c.getInt(2))
        }
    }

    @Test
    fun fundBecomesSavings() {
        seed("fund", "FUND")
        seed("credit", "CREDIT")
        seed("debt", "DEBT")

        MIGRATION_4_5.migrate(raw())

        assertEquals(AccountType.SAVINGS.name, typeOf("fund"))
        assertEquals(AccountType.CREDIT.name, typeOf("credit"))
        assertEquals(AccountType.DEBT.name, typeOf("debt"))
    }

    @Test
    fun softDeletedAccountAlsoMigrated() {
        seed("trashed", "FUND", deletedAt = 1_700_000_000_000)

        MIGRATION_4_5.migrate(raw())

        assertEquals(AccountType.SAVINGS.name, typeOf("trashed"))
        raw().query("SELECT `deletedAt` FROM `account` WHERE `id`='trashed'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1_700_000_000_000L, c.getLong(0))
        }
    }

    @Test
    fun keepsExistingData() {
        seed("a1", "FUND")

        MIGRATION_4_5.migrate(raw())

        raw().query("SELECT `name`,`initialBalance`,`deletedAt` FROM `account` WHERE `id`='a1'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("a1", c.getString(0))
            assertEquals(1000L, c.getLong(1))
            assertNull(if (c.isNull(2)) null else c.getLong(2))
        }
    }

    @Test
    fun readableByDaoAfterMigration() {
        seed("fund", "FUND")
        // 一路迁到当前 schema 版本：Room 读行时按最新实体表结构取值，
        // 只跑到 4→5 会缺 v1.9.0 的 debtDirection/note/dueAt 三列
        MIGRATION_4_5.migrate(raw())
        MIGRATION_5_6.migrate(raw())
        MIGRATION_6_7.migrate(raw())

        val entity = runBlocking { db.accountDao().byId("fund") }
        assertEquals(AccountType.SAVINGS.name, entity?.type)
        assertEquals(0L, entity?.costCents)
        assertEquals(0L, entity?.marketValueCents)
        assertEquals(true, entity?.includeInNetAssets)
    }
}
