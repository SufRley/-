package com.xxjz.ledger.domain.usecase

import com.xxjz.ledger.core.common.AppResult
import com.xxjz.ledger.data.datastore.SettingsDataStore
import com.xxjz.ledger.data.local.TestDatabaseFactory
import com.xxjz.ledger.data.local.db.AppDatabase
import com.xxjz.ledger.data.local.entity.AccountEntity
import com.xxjz.ledger.data.local.entity.CategoryEntity
import com.xxjz.ledger.data.local.entity.TxnEntity
import com.xxjz.ledger.data.repository.BackupRepositoryImpl
import com.xxjz.ledger.data.repository.DebtToAccountMigrator
import com.xxjz.ledger.domain.model.AccountDto
import com.xxjz.ledger.domain.model.BackupPayload
import com.xxjz.ledger.domain.model.BudgetDto
import com.xxjz.ledger.domain.model.CategoryDto
import com.xxjz.ledger.domain.model.TxnDto
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** M8 覆盖导入（AC-73）：整库替换、失败整体回滚、设置随备份恢复。 */
@RunWith(RobolectricTestRunner::class)
class RestoreBackupUseCaseTest {
    private lateinit var db: AppDatabase
    private lateinit var settings: SettingsDataStore
    private lateinit var repo: BackupRepositoryImpl
    private lateinit var useCase: RestoreBackupUseCase

    @Before
    fun setUp() {
        db = TestDatabaseFactory.create()
        settings = mockk(relaxed = true)
        repo = BackupRepositoryImpl(db, DebtToAccountMigrator(db))
        useCase = RestoreBackupUseCase(repo, settings)
    }

    @After
    fun tearDown() {
        if (this::db.isInitialized) db.close()
    }

    private fun txn(
        id: String,
        amount: Long,
        note: String = "",
        deletedAt: Long? = null,
    ) = TxnEntity(
        id = id,
        type = "EXPENSE",
        amount = amount,
        categoryId = "c1",
        accountId = "a1",
        toAccountId = null,
        fee = 0,
        dateTime = 1_700_000_000_000,
        dateKey = 20261113,
        note = note,
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

    private fun account(
        id: String,
        name: String,
    ) = AccountEntity(
        id = id,
        name = name,
        type = "CASH",
        initialBalance = 0,
        icon = "",
        color = "",
        cardLimit = null,
        billDay = null,
        repayDay = null,
        hidden = false,
        sortOrder = 0,
        deletedAt = null,
    )

    private fun category(id: String) =
        CategoryEntity(
            id = id,
            name = id,
            parentId = null,
            type = "EXPENSE",
            icon = "",
            color = "",
            sortOrder = 0,
            hidden = false,
            pinned = false,
            usageCount = 0,
        )

    private fun existingData() =
        runTest {
            db.accountDao().insert(account("a1", "旧账户"))
            db.categoryDao().insert(category("c1"))
            db.txnDao().insertAll(listOf(txn("old1", 111), txn("old2", 222)))
            db.budgetDao().insert(
                com.xxjz.ledger.data.local.entity.BudgetEntity(
                    id = "b_old",
                    monthKey = 202611,
                    name = "旧预算",
                    period = "MONTH",
                    scope = "TOTAL",
                    categoryIds = "",
                    amount = 1_000,
                    enabled = true,
                    deletedAt = null,
                    sortOrder = 0,
                ),
            )
        }

    @Test
    fun `覆盖导入后只剩备份里的数据`() =
        runTest {
            existingData()

            val payload =
                BackupPayload(
                    schemaVersion = 4,
                    createdAt = 1_800_000_000_000,
                    accounts = listOf(AccountDto(id = "a9", name = "新账户")),
                    categories = listOf(CategoryDto(id = "c9", name = "新分类")),
                    txns =
                        listOf(
                            TxnDto(id = "n1", amount = 900, accountId = "a9", categoryId = "c9", dateKey = 20261101),
                            TxnDto(id = "n2", amount = 100, accountId = "a9", categoryId = "c9", dateKey = 20261102),
                        ),
                )

            val result = useCase(payload)
            assertTrue(result is AppResult.Success)

            val txns = db.txnDao().listAllIncludingDeleted()
            assertEquals(listOf("n1", "n2"), txns.map { it.id }.sorted())
            assertEquals(1, db.accountDao().listAll().size)
            assertEquals("a9", db.accountDao().listAll().first().id)
            // 旧预算被清掉了，备份里没有预算 → 一条都不剩
            assertTrue(db.budgetDao().listAllIncludingDeleted().isEmpty())
            coVerify(exactly = 1) { settings.restoreFrom(any()) }
        }

    @Test
    fun `写入阶段抛异常时整体回滚，旧数据一条不少（AC-73）`() =
        runTest {
            existingData()
            repo.faultInjector = { throw IllegalStateException("模拟写入失败") }

            val payload =
                BackupPayload(
                    schemaVersion = 4,
                    accounts = listOf(AccountDto(id = "a9", name = "新账户")),
                    txns = listOf(TxnDto(id = "n1", amount = 900, accountId = "a9")),
                )

            val result = useCase(payload)
            assertTrue(result is AppResult.Failure)

            // 事务回滚：旧账户、旧账目、旧预算都还在，新数据一条没进
            assertEquals(listOf("a1"), db.accountDao().listAll().map { it.id })
            assertEquals(listOf("old1", "old2"), db.txnDao().listAllIncludingDeleted().map { it.id }.sorted())
            assertEquals(1, db.budgetDao().listAllIncludingDeleted().size)
            assertTrue(db.accountDao().byId("a9") == null)
            // 数据没落库，设置也不能动
            coVerify(exactly = 0) { settings.restoreFrom(any()) }
        }

    @Test
    fun `空备份拒绝导入，不会把用户数据清空`() =
        runTest {
            existingData()

            val result = useCase(BackupPayload(schemaVersion = 4))
            assertTrue(result is AppResult.Failure)
            assertEquals(2, db.txnDao().listAllIncludingDeleted().size)
            assertEquals(1, db.accountDao().listAll().size)
        }

    @Test
    fun `回收站里的账目也会原样导入`() =
        runTest {
            val payload =
                BackupPayload(
                    schemaVersion = 4,
                    accounts = listOf(AccountDto(id = "a1", name = "现金")),
                    txns =
                        listOf(
                            TxnDto(id = "live", amount = 100, deletedAt = null),
                            TxnDto(id = "gone", amount = 200, deletedAt = 1_700_000_500_000),
                        ),
                    budgets = listOf(BudgetDto(id = "b1", monthKey = 202611, amount = 5_000, deletedAt = 1_700_000_600_000)),
                )

            useCase(payload)

            val gone = db.txnDao().byId("gone")
            assertEquals(1_700_000_500_000, gone?.deletedAt)
            assertEquals(1, db.budgetDao().listAllIncludingDeleted().size)
            assertEquals(1_700_000_600_000, db.budgetDao().listAllIncludingDeleted().first().deletedAt)
        }

    @Test
    fun `重复导入同一份备份结果不变（幂等）`() =
        runTest {
            val payload =
                BackupPayload(
                    schemaVersion = 4,
                    accounts = listOf(AccountDto(id = "a1", name = "现金")),
                    txns = listOf(TxnDto(id = "n1", amount = 900)),
                )

            useCase(payload)
            useCase(payload)

            assertEquals(1, db.txnDao().listAllIncludingDeleted().size)
            assertEquals(1, db.accountDao().listAll().size)
        }
}
