package com.xxjz.ledger.data.local

import com.xxjz.ledger.data.local.entity.AccountEntity
import com.xxjz.ledger.data.local.entity.BudgetEntity
import com.xxjz.ledger.data.local.entity.TxnEntity
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

/** M8 账户进回收站（AC-34）：软删除后不出现在列表，还原时连带的流水一起回来。 */
@RunWith(RobolectricTestRunner::class)
class AccountTrashDaoTest {
    private lateinit var db: com.xxjz.ledger.data.local.db.AppDatabase

    @Before
    fun setUp() {
        db = TestDatabaseFactory.create()
    }

    @After
    fun tearDown() {
        if (this::db.isInitialized) db.close()
    }

    private fun account(
        id: String,
        hidden: Boolean = false,
    ) = AccountEntity(
        id = id,
        name = id,
        type = "CASH",
        initialBalance = 0,
        icon = "",
        color = "",
        cardLimit = null,
        billDay = null,
        repayDay = null,
        hidden = hidden,
        sortOrder = 0,
        deletedAt = null,
    )

    private fun txn(
        id: String,
        accountId: String,
        toAccountId: String? = null,
        deletedAt: Long? = null,
    ) = TxnEntity(
        id = id,
        type = if (toAccountId == null) "EXPENSE" else "TRANSFER",
        amount = 100,
        categoryId = null,
        accountId = accountId,
        toAccountId = toAccountId,
        fee = 0,
        dateTime = 1_700_000_000_000,
        dateKey = 20261113,
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
        createdAt = 1_700_000_000_000,
        updatedAt = 1_700_000_000_000,
    )

    @Test
    fun `软删除的账户不出现在可见列表与计数里`() =
        runTest {
            db.accountDao().insert(account("a1"))
            db.accountDao().insert(account("a2", hidden = true))

            db.accountDao().softDelete("a1", 1_000L)

            assertTrue(db.accountDao().observeVisible().first().none { it.id == "a1" })
            assertTrue(db.accountDao().observeAll().first().none { it.id == "a1" })
            // count 只排除回收站里的账户；a2 虽然隐藏但仍算一个可用账户
            assertEquals(1, db.accountDao().count())
            // 备份用查询仍然能看到它
            assertEquals(2, db.accountDao().listAllIncludingDeleted().size)
            assertEquals(listOf("a1"), db.accountDao().observeDeleted().first().map { it.id })
        }

    @Test
    fun `还原账户时把同一次删除的流水一起捞回来`() =
        runTest {
            db.accountDao().insert(account("a1"))
            db.accountDao().insert(account("a2"))
            db.txnDao().insertAll(
                listOf(
                    txn("t_own", "a1"),
                    txn("t_transfer_out", "a1", "a2"),
                    txn("t_transfer_in", "a2", "a1"),
                    txn("t_other", "a2"),
                ),
            )

            db.txnDao().softDeleteByAccount("a1", 5_000L)
            db.accountDao().softDelete("a1", 5_000L)
            assertEquals(1, db.txnDao().listAllIncludingDeleted().count { it.deletedAt == null })

            // 用户又单独删了一笔别的账户的流水；还原 a1 不该把它也捞回来
            db.txnDao().softDelete("t_other", 6_000L)

            val deletedAt = db.accountDao().deletedAtOf("a1")
            assertEquals(5_000L, deletedAt)
            db.accountDao().restore("a1")
            db.txnDao().restoreByAccount("a1", deletedAt!!, 7_000L)

            val live = db.txnDao().listAllIncludingDeleted().filter { it.deletedAt == null }.map { it.id }.sorted()
            assertEquals(listOf("t_own", "t_transfer_in", "t_transfer_out"), live)
            assertNotNull(db.txnDao().byId("t_other")?.deletedAt)
            assertNull(db.accountDao().deletedAtOf("a1"))
        }

    @Test
    fun `彻底删除账户会连带删掉它的流水`() =
        runTest {
            db.accountDao().insert(account("a1"))
            db.txnDao().insertAll(listOf(txn("t1", "a1"), txn("t2", "a1")))
            db.accountDao().softDelete("a1", 1_000L)

            db.txnDao().hardDeleteByAccount("a1")
            db.accountDao().delete("a1")

            assertTrue(db.txnDao().listAllIncludingDeleted().isEmpty())
            assertNull(db.accountDao().byId("a1"))
        }

    @Test
    fun `预算回收站只列出软删除的行`() =
        runTest {
            val base =
                BudgetEntity(
                    id = "b1",
                    monthKey = 202611,
                    name = "x",
                    period = "MONTH",
                    scope = "TOTAL",
                    categoryIds = "",
                    amount = 100,
                    enabled = true,
                    deletedAt = null,
                    sortOrder = 0,
                )
            db.budgetDao().insert(base)
            db.budgetDao().insert(base.copy(id = "b2", deletedAt = 9_000L))

            assertEquals(listOf("b2"), db.budgetDao().observeDeleted().first().map { it.id })
            assertEquals(2, db.budgetDao().listAllIncludingDeleted().size)
        }
}
