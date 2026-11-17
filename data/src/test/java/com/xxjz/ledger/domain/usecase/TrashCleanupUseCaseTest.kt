package com.xxjz.ledger.domain.usecase

import com.xxjz.ledger.core.common.AppResult
import com.xxjz.ledger.data.datastore.SettingsDataStore
import com.xxjz.ledger.data.local.TestDatabaseFactory
import com.xxjz.ledger.data.local.db.AppDatabase
import com.xxjz.ledger.data.local.entity.AccountEntity
import com.xxjz.ledger.data.local.entity.BudgetEntity
import com.xxjz.ledger.data.local.entity.TagEntity
import com.xxjz.ledger.data.local.entity.TxnEntity
import com.xxjz.ledger.data.local.entity.TxnTagEntity
import com.xxjz.ledger.data.repository.BackupRepositoryImpl
import com.xxjz.ledger.data.repository.DebtToAccountMigrator
import com.xxjz.ledger.domain.model.AppSettings
import com.xxjz.ledger.domain.model.needsBackupReminder
import io.mockk.coEvery
import io.mockk.mockk
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

/** M8 回收站定时清理（AC-76）：按保留天数物理删除，永久时不动。 */
@RunWith(RobolectricTestRunner::class)
class TrashCleanupUseCaseTest {
    private lateinit var db: AppDatabase
    private lateinit var settings: SettingsDataStore
    private lateinit var useCase: TrashCleanupUseCase

    private val now = 1_800_000_000_000L
    private val day = TrashCleanupUseCase.DAY_MILLIS

    @Before
    fun setUp() {
        db = TestDatabaseFactory.create()
        settings = mockk(relaxed = true)
        useCase = TrashCleanupUseCase(BackupRepositoryImpl(db, DebtToAccountMigrator(db)), settings)
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
        amount = 100,
        categoryId = null,
        accountId = "a1",
        toAccountId = null,
        fee = 0,
        dateTime = now,
        dateKey = 20270101,
        note = "",
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
        createdAt = now,
        updatedAt = now,
    )

    private suspend fun seed() {
        db.txnDao().insertAll(
            listOf(
                txn("live"),
                txn("recent", deletedAt = now - 5 * day),
                txn("stale", deletedAt = now - 40 * day),
            ),
        )
        db.tagDao().insert(TagEntity(id = "tag1", name = "x", color = "", usageCount = 0))
        db.tagDao().linkTags(listOf(TxnTagEntity(txnId = "stale", tagId = "tag1")))
        db.accountDao().insert(
            AccountEntity(
                id = "a_old",
                name = "旧卡",
                type = "CASH",
                initialBalance = 0,
                icon = "",
                color = "",
                cardLimit = null,
                billDay = null,
                repayDay = null,
                hidden = false,
                sortOrder = 0,
                deletedAt = now - 45 * day,
            ),
        )
        db.budgetDao().insert(
            BudgetEntity(
                id = "b_old",
                monthKey = 202611,
                name = "旧预算",
                period = "MONTH",
                scope = "TOTAL",
                categoryIds = "",
                amount = 1,
                enabled = true,
                deletedAt = now - 31 * day,
                sortOrder = 0,
            ),
        )
    }

    @Test
    fun `超过保留天数的记录被物理删除，未到期的保留`() =
        runTest {
            coEvery { settings.current() } returns AppSettings(recycleBinDays = 30)
            seed()

            val result = useCase(now)
            assertEquals(3, (result as AppResult.Success).data)

            val ids = db.txnDao().listAllIncludingDeleted().map { it.id }.sorted()
            assertEquals(listOf("live", "recent"), ids)
            assertNull(db.accountDao().byId("a_old"))
            assertTrue(db.budgetDao().listAllIncludingDeleted().isEmpty())
            // 孤儿标签关联被清掉
            assertTrue(db.tagDao().tagIdsOf("stale").isEmpty())
            // 标签本身不动
            assertNotNull(db.tagDao().listAll().firstOrNull())
        }

    @Test
    fun `保留天数设为永久时一条都不删`() =
        runTest {
            coEvery { settings.current() } returns AppSettings(recycleBinDays = 0)
            seed()

            val result = useCase(now)
            assertEquals(0, (result as AppResult.Success).data)
            assertEquals(3, db.txnDao().listAllIncludingDeleted().size)
            assertNotNull(db.accountDao().byId("a_old"))
        }

    @Test
    fun `保留 60 天时 40 天前的不删`() =
        runTest {
            coEvery { settings.current() } returns AppSettings(recycleBinDays = 60)
            seed()
            useCase(now)
            assertNotNull(db.txnDao().byId("stale"))
            // 账户是 45 天前删的，60 天保留期内
            assertNotNull(db.accountDao().byId("a_old"))
        }

    @Test
    fun `边界：刚好等于保留时长时保留（严格小于才删）`() =
        runTest {
            coEvery { settings.current() } returns AppSettings(recycleBinDays = 30)
            db.txnDao().insert(txn("edge", deletedAt = now - 30 * day))
            db.txnDao().insert(txn("over", deletedAt = now - 30 * day - 1))
            useCase(now)
            assertNotNull(db.txnDao().byId("edge"))
            assertNull(db.txnDao().byId("over"))
        }
}

/** M8 备份提醒（M8-T8）。 */
class BackupReminderTest {
    private val now = 1_800_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    @Test
    fun `从没备份过要提醒`() {
        assertTrue(needsBackupReminder(enabled = true, lastBackupAt = 0, now = now))
    }

    @Test
    fun `关掉开关后不再提醒`() {
        assertFalse(needsBackupReminder(enabled = false, lastBackupAt = 0, now = now))
        assertFalse(needsBackupReminder(enabled = false, lastBackupAt = now - 30 * day, now = now))
    }

    @Test
    fun `未满 7 天不提醒，满 7 天提醒`() {
        assertFalse(needsBackupReminder(enabled = true, lastBackupAt = now - 6 * day, now = now))
        assertTrue(needsBackupReminder(enabled = true, lastBackupAt = now - 7 * day, now = now))
    }
}
