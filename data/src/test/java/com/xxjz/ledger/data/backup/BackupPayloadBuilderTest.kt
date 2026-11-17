package com.xxjz.ledger.data.backup

import com.xxjz.ledger.data.local.TestDatabaseFactory
import com.xxjz.ledger.data.local.db.AppDatabase
import com.xxjz.ledger.data.local.entity.AccountEntity
import com.xxjz.ledger.data.local.entity.BudgetEntity
import com.xxjz.ledger.data.local.entity.CategoryEntity
import com.xxjz.ledger.data.local.entity.TagEntity
import com.xxjz.ledger.data.local.entity.TxnEntity
import com.xxjz.ledger.data.local.entity.TxnTagEntity
import com.xxjz.ledger.domain.model.AppSettings
import com.xxjz.ledger.domain.model.BackupPayload
import com.xxjz.ledger.domain.model.DefaultTimeMode
import com.xxjz.ledger.domain.model.TxnType
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** M8 备份载荷（AC-68）：包含回收站、设置快照正确、版本号写入。 */
@RunWith(RobolectricTestRunner::class)
class BackupPayloadBuilderTest {
    private lateinit var db: AppDatabase

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
        deletedAt: Long? = null,
    ) = TxnEntity(
        id = id,
        type = "EXPENSE",
        amount = 500,
        categoryId = "c1",
        accountId = "a1",
        toAccountId = null,
        fee = 0,
        dateTime = 1_700_000_000_000,
        dateKey = 20261113,
        note = id,
        memo = "",
        merchantId = null,
        merchantName = null,
        currency = "CNY",
        ledgerId = null,
        excludedFromStats = false,
        pendingReimbursement = false,
        reimbursedAmount = 0,
        reimbursedAt = null,
        refundOfTxnId = null,
        parentTxnId = null,
        debtId = null,
        deletedAt = deletedAt,
        createdAt = 1_700_000_000_000,
        updatedAt = 1_700_000_000_000,
    )

    private suspend fun seed() {
        db.accountDao().insert(
            AccountEntity(
                id = "a1",
                name = "现金",
                type = "CASH",
                initialBalance = 10_000,
                icon = "",
                color = "",
                cardLimit = null,
                billDay = null,
                repayDay = null,
                hidden = false,
                sortOrder = 0,
                deletedAt = null,
            ),
        )
        db.categoryDao().insert(
            CategoryEntity(
                id = "c1",
                name = "餐饮",
                parentId = null,
                type = "EXPENSE",
                icon = "",
                color = "",
                sortOrder = 0,
                hidden = false,
                pinned = false,
                usageCount = 3,
            ),
        )
        db.txnDao().insertAll(listOf(txn("live"), txn("gone", deletedAt = 1_700_000_100_000)))
        db.tagDao().insert(TagEntity(id = "tag1", name = "出差", color = "", usageCount = 1))
        db.tagDao().linkTags(listOf(TxnTagEntity(txnId = "live", tagId = "tag1")))
        db.budgetDao().insert(
            BudgetEntity(
                id = "b1",
                monthKey = 202611,
                name = "本月预算",
                period = "MONTH",
                scope = "TOTAL",
                categoryIds = "",
                amount = 200_000,
                enabled = true,
                deletedAt = 1_700_000_200_000,
                sortOrder = 0,
            ),
        )
    }

    @Test
    fun `导出的载荷包含回收站数据与设置快照`() =
        runTest {
            seed()
            val settings =
                AppSettings(
                    defaultAccountId = "a1",
                    defaultTimeMode = DefaultTimeMode.FIXED,
                    fixedTimeMinutes = 600,
                    decimalPlaces = 2,
                    amountColorsReversed = true,
                    themeMode = "DARK",
                    recycleBinDays = 60,
                    backupReminderEnabled = false,
                    lastTxnType = TxnType.INCOME,
                    savingGoalCents = 888_800,
                )

            val payload = BackupPayloadBuilder(db).build(settings, now = 1_800_000_000_000, appVersion = "9.9.9")

            assertEquals(BackupPayload.CURRENT_PAYLOAD_VERSION, payload.payloadVersion)
            assertEquals(AppDatabase.SCHEMA_VERSION, payload.schemaVersion)
            assertEquals("9.9.9", payload.appVersion)
            assertEquals(1_800_000_000_000, payload.createdAt)

            // 回收站里的那条也要带上，否则导回去就少了一笔
            assertEquals(2, payload.txns.size)
            assertEquals(1_700_000_100_000, payload.txns.first { it.id == "gone" }.deletedAt)
            // 统计只算「活着」的记录
            assertEquals(1, payload.stats.txnCount)
            assertEquals(1, payload.stats.accountCount)
            assertEquals(1, payload.stats.categoryCount)

            assertEquals(1, payload.tags.size)
            assertEquals(1, payload.txnTags.size)
            assertEquals("tag1", payload.txnTags.first().tagId)
            assertEquals(1, payload.budgets.size)
            assertEquals(1_700_000_200_000, payload.budgets.first().deletedAt)

            assertEquals("a1", payload.settings.defaultAccountId)
            assertEquals("FIXED", payload.settings.defaultTimeMode)
            assertEquals(600, payload.settings.fixedTimeMinutes)
            assertTrue(payload.settings.amountColorsReversed)
            assertEquals("DARK", payload.settings.themeMode)
            assertEquals(60, payload.settings.recycleBinDays)
            assertEquals(false, payload.settings.backupReminderEnabled)
            assertEquals("INCOME", payload.settings.lastTxnType)
            assertEquals(888_800, payload.settings.savingGoalCents)
        }

    @Test
    fun `空库也能导出，不会崩`() =
        runTest {
            val payload = BackupPayloadBuilder(db).build(AppSettings())
            assertTrue(payload.txns.isEmpty())
            assertTrue(payload.accounts.isEmpty())
            assertNull(payload.settings.defaultAccountId)
            assertEquals("SYSTEM", payload.settings.themeMode)
        }
}
