package com.xxjz.ledger.data.local

import com.xxjz.ledger.data.local.entity.AccountEntity
import com.xxjz.ledger.data.local.entity.TxnEntity
import com.xxjz.ledger.domain.model.AccountType
import com.xxjz.ledger.domain.model.TxnType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** M3 账户数据层：余额重算、隐藏、排序、删除级联（AC-21 ~ AC-24）。 */
@RunWith(RobolectricTestRunner::class)
class AccountDaoTest {
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
        initial: Long = 0,
        order: Int = 0,
        hidden: Boolean = false,
    ) = AccountEntity(
        id = id,
        name = id,
        type = AccountType.SAVINGS.name,
        initialBalance = initial,
        icon = "",
        color = "",
        cardLimit = null,
        billDay = null,
        repayDay = null,
        hidden = hidden,
        sortOrder = order,
    )

    private fun txn(
        id: String,
        amount: Long,
        type: TxnType,
        accountId: String,
        toAccountId: String? = null,
        fee: Long = 0,
    ) = TxnEntity(
        id = id,
        type = type.name,
        amount = amount,
        categoryId = null,
        accountId = accountId,
        toAccountId = toAccountId,
        fee = fee,
        dateTime = 1_700_000_000_000L,
        dateKey = 20231114,
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
        deletedAt = null,
        createdAt = 1_700_000_000_000L,
        updatedAt = 1_700_000_000_000L,
    )

    @Test
    fun `余额等于初始余额加减收支与转账`() =
        runTest {
            db.accountDao().insertAll(listOf(account("a", 10_000), account("b", 0)))
            db.txnDao().insertAll(
                listOf(
                    txn("i", 5_000, TxnType.INCOME, "a"),
                    txn("e", 3_000, TxnType.EXPENSE, "a"),
                    txn("t", 2_000, TxnType.TRANSFER, "a", toAccountId = "b", fee = 100),
                ),
            )
            assertEquals(9_900L, db.accountDao().balanceOf("a")?.balance)
            assertEquals(2_000L, db.accountDao().balanceOf("b")?.balance)
        }

    @Test
    fun `改初始余额后余额立即重算`() =
        runTest {
            db.accountDao().insert(account("a", 10_000))
            db.txnDao().insert(txn("e", 3_000, TxnType.EXPENSE, "a"))
            assertEquals(7_000L, db.accountDao().balanceOf("a")?.balance)
            db.accountDao().update(account("a", 20_000))
            assertEquals(17_000L, db.accountDao().balanceOf("a")?.balance)
        }

    @Test
    fun `隐藏账户不在可见列表但仍在全量列表`() =
        runTest {
            db.accountDao().insertAll(listOf(account("a"), account("b")))
            db.accountDao().setHidden("b", true)
            assertEquals(listOf("a"), db.accountDao().observeVisible().first().map { it.id })
            assertEquals(2, db.accountDao().observeAll().first().size)
        }

    @Test
    fun `排序值决定列表顺序`() =
        runTest {
            db.accountDao().insertAll(listOf(account("a", order = 2), account("b", order = 1)))
            assertEquals(listOf("b", "a"), db.accountDao().observeAll().first().map { it.id })
            db.accountDao().setSortOrder("a", 0)
            assertEquals(listOf("a", "b"), db.accountDao().observeAll().first().map { it.id })
        }

    @Test
    fun `删除账户时级联软删除其全部流水`() =
        runTest {
            db.accountDao().insertAll(listOf(account("a"), account("b")))
            db.txnDao().insertAll(
                listOf(
                    txn("e", 1_000, TxnType.EXPENSE, "a"),
                    txn("t", 2_000, TxnType.TRANSFER, "a", toAccountId = "b"),
                    txn("x", 500, TxnType.EXPENSE, "b"),
                ),
            )
            assertEquals(2, db.txnDao().countByAccount("a"))

            db.txnDao().softDeleteByAccount("a", 99L)
            db.accountDao().delete("a")

            assertNull(db.accountDao().byId("a"))
            assertEquals(0, db.txnDao().countByAccount("a"))
            assertEquals(1, db.txnDao().countByAccount("b"))
            // 转入账户的余额不再受已删转账影响，只剩自己那笔 500 支出
            assertEquals(-500L, db.accountDao().balanceOf("b")?.balance)
            assertEquals(1, db.txnDao().listInRange(0, Long.MAX_VALUE).size)
        }

    @Test
    fun `软删除后原账户余额回落到初始值`() =
        runTest {
            db.accountDao().insert(account("a", 5_000))
            db.txnDao().insert(txn("e", 2_000, TxnType.EXPENSE, "a"))
            assertEquals(3_000L, db.accountDao().balanceOf("a")?.balance)
            db.txnDao().softDeleteByAccount("a", 5L)
            assertEquals(5_000L, db.accountDao().balanceOf("a")?.balance)
            assertNotNull(db.accountDao().byId("a"))
        }
}
