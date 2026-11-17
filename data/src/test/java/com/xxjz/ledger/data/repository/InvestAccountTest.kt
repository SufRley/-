package com.xxjz.ledger.data.repository

import com.xxjz.ledger.data.local.TestDatabaseFactory
import com.xxjz.ledger.data.local.db.AppDatabase
import com.xxjz.ledger.data.local.entity.AccountEntity
import com.xxjz.ledger.domain.model.AccountType
import com.xxjz.ledger.domain.model.Txn
import com.xxjz.ledger.domain.model.TxnType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 投资账户成本联动（v1.8.0 / PRD D15）。
 *
 * 规则：买入 = 转入（成本 +金额），卖出 = 转出（成本 −金额）；改市值只改市值、**不生成流水**。
 * 这里跑真实 Room + 真实仓库实现，覆盖新增/编辑/删除/还原/硬删五条路径——
 * 成本一旦漏算或重复算，净资产与盈亏会长期错下去，靠 UI 是看不出来的。
 */
@RunWith(RobolectricTestRunner::class)
class InvestAccountTest {
    private lateinit var db: AppDatabase
    private lateinit var accountRepo: AccountRepositoryImpl
    private lateinit var txnRepo: TxnRepositoryImpl
    private lateinit var eventStore: AssetEventStore

    private val now = 1_700_000_000_000L
    private val dateKey = 20261114

    @Before
    fun setUp() {
        db = TestDatabaseFactory.create()
        eventStore = AssetEventStore(db.metaDao())
        accountRepo = AccountRepositoryImpl(db, db.accountDao(), eventStore)
        txnRepo = TxnRepositoryImpl(db.txnDao(), db.categoryDao(), accountRepo)

        runBlocking {
            db.accountDao().insertAll(
                listOf(
                    account("cash", AccountType.CASH),
                    account("invest", AccountType.INVEST),
                ),
            )
        }
    }

    @After
    fun tearDown() {
        if (this::db.isInitialized) db.close()
    }

    private fun account(
        id: String,
        type: AccountType,
    ) = AccountEntity(
        id = id,
        name = id,
        type = type.name,
        initialBalance = 0,
        icon = "",
        color = "",
        cardLimit = null,
        billDay = null,
        repayDay = null,
        hidden = false,
        sortOrder = 0,
    )

    private fun transfer(
        id: String,
        amount: Long,
        from: String,
        to: String,
        fee: Long = 0,
    ) = Txn(
        id = id,
        type = TxnType.TRANSFER,
        amount = amount,
        categoryId = null,
        accountId = from,
        toAccountId = to,
        fee = fee,
        dateTime = now,
        dateKey = dateKey,
        createdAt = now,
        updatedAt = now,
    )

    private suspend fun costOf(): Long = db.accountDao().byId("invest")!!.costCents

    private suspend fun marketValueOf(): Long = db.accountDao().byId("invest")!!.marketValueCents

    private suspend fun firstCount(): Int = db.txnDao().observeTotalCount().first()

    @Test
    fun buyIncreasesCost() {
        runBlocking {
            txnRepo.insert(transfer("t1", 1_000_00, from = "cash", to = "invest"))
            assertEquals(1_000_00, costOf())

            txnRepo.insert(transfer("t2", 500_00, from = "cash", to = "invest"))
            assertEquals(1_500_00, costOf())
        }
    }

    @Test
    fun sellDecreasesCost() {
        runBlocking {
            txnRepo.insert(transfer("t1", 1_000_00, from = "cash", to = "invest"))
            txnRepo.insert(transfer("t2", 600_00, from = "invest", to = "cash"))
            assertEquals(400_00, costOf())
        }
    }

    @Test
    fun costNeverNegative() {
        runBlocking {
            txnRepo.insert(transfer("t1", 100_00, from = "invest", to = "cash"))
            assertEquals(0L, costOf(), "卖出多于成本时成本取 0，不能变负数")
        }
    }

    @Test
    fun editRewritesCostWithoutDrift() {
        runBlocking {
            val t1 = transfer("t1", 500_00, from = "cash", to = "invest")
            txnRepo.insert(t1)
            // 把 500 改成 700：旧影响先撤销再加新值，结果必须是 700 而不是 1200
            txnRepo.update(t1.copy(amount = 700_00, updatedAt = now + 1))
            assertEquals(700_00, costOf())
        }
    }

    @Test
    fun deleteRollsBackCost() {
        runBlocking {
            txnRepo.insert(transfer("t1", 1_000_00, from = "cash", to = "invest"))
            txnRepo.softDelete("t1", now + 1)
            assertEquals(0L, costOf())

            txnRepo.restore("t1", now + 2)
            assertEquals(1_000_00, costOf(), "回收站还原要把成本加回去")

            txnRepo.hardDelete("t1")
            assertEquals(0L, costOf(), "彻底删除同样要回退")
        }
    }

    @Test
    fun nonInvestAccountsUnaffected() {
        runBlocking {
            txnRepo.insert(transfer("t1", 800_00, from = "cash", to = "cash"))
            assertEquals(0L, costOf(), "非投资账户的转账不碰投资成本")
        }
    }

    @Test
    fun updateMarketValueDoesNotCreateTxn() {
        runBlocking {
            txnRepo.insert(transfer("t1", 1_000_00, from = "cash", to = "invest"))
            assertEquals(1_000_00, costOf())

            accountRepo.updateMarketValue("invest", 1_350_00, now + 10)

            assertEquals(1_350_00, marketValueOf())
            assertEquals(1_000_00, costOf(), "改市值不改成本")
            assertEquals(1, firstCount(), "改市值不能生成流水")

            val events = eventStore.listAll()
            assertEquals(1, events.size)
            // PRD D14：变化记录里「金额」显示的是本次调整到的市值（新市值），不是差额
            assertEquals(1_350_00, events.first().deltaCents)
        }
    }

    @Test
    fun marketValueEventSkippedWhenExcluded() {
        runBlocking {
            val invest = db.accountDao().byId("invest")!!
            db.accountDao().update(invest.copy(includeInNetAssets = false))

            accountRepo.updateMarketValue("invest", 999_00, now + 10)

            assertTrue(eventStore.listAll().isEmpty(), "不计入净资产时改市值不产生资产变化记录")
            assertEquals(999_00, marketValueOf(), "市值本身仍然要改")
        }
    }

    @Test
    fun marketValueOnNonInvestIsNoop() {
        runBlocking {
            accountRepo.updateMarketValue("cash", 500_00, now + 10)
            assertEquals(0L, db.accountDao().byId("cash")!!.marketValueCents)

            accountRepo.adjustInvestCost("cash", 500_00, now + 10)
            assertEquals(0L, db.accountDao().byId("cash")!!.costCents)
        }
    }

    @Test
    fun marketValueClampedToZero() {
        runBlocking {
            accountRepo.updateMarketValue("invest", -100_00, now + 10)
            assertEquals(0L, marketValueOf())
        }
    }

    @Test
    fun differentEventsHaveDistinctKeys() {
        runBlocking {
            accountRepo.updateMarketValue("invest", 100_00, now + 10)
            accountRepo.updateMarketValue("invest", 300_00, now + 11)
            val events = eventStore.listAll()
            assertEquals(2, events.size)
            assertNotEquals(events[0].id, events[1].id)
            assertEquals(events.sortedByDescending { it.at }.map { it.at }, events.map { it.at })
        }
    }
}
