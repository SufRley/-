package com.xxjz.ledger.domain.usecase

import com.xxjz.ledger.core.common.AppError
import com.xxjz.ledger.core.common.AppResult
import com.xxjz.ledger.domain.model.Account
import com.xxjz.ledger.domain.model.AccountType
import com.xxjz.ledger.domain.model.DebtAction
import com.xxjz.ledger.domain.model.DebtDirection
import com.xxjz.ledger.domain.model.Txn
import com.xxjz.ledger.domain.model.TxnType
import com.xxjz.ledger.testutil.FakeAccountRepository
import com.xxjz.ledger.testutil.FakeTxnRepository
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * v1.9.0 借贷重构（PRD 2.2 / 2.3 / 2.5）。
 *
 * 核心口径：一个借贷账户 = 一个人，方向建账户时锁定；
 * 四种操作（借出 / 收款 / 借入 / 还款）**都不改变净资产**，只有坏账核销会减。
 */
@DisplayName("借贷用例")
class DebtUseCasesTest {
    private val txnRepo = FakeTxnRepository()
    private val accountRepo =
        FakeAccountRepository().also { repo ->
            // 借贷账户的「待收/待还」余额由流水推出来，和 AccountDao 的聚合 SQL 同一口径
            repo.txns = txnRepo
        }

    private val recordOp = RecordDebtOpUseCase(accountRepo, txnRepo)
    private val saveAccount = SaveDebtAccountUseCase(accountRepo, recordOp)
    private val writeOff = WriteOffDebtUseCase(accountRepo, recordOp)

    /**
     * 现金 10000 元，作为所有数例的起点。
     *
     * 放在 `@BeforeEach` 而不是 `init`：`init` 与属性初始化器的先后顺序由声明位置决定，
     * 建在这里的现金账户会被后面的 `accountRepo` 重新赋值覆盖掉，测试就拿到一个空账户表。
     */
    @BeforeEach
    fun seedCash() {
        accountRepo.items["cash"] = cashAccount("cash", 10_000_00)
    }

    private fun cashAccount(
        id: String,
        balance: Long,
    ) = Account(id = id, name = id, type = AccountType.CASH, balance = balance)

    /** 建一个借贷账户（本金走 [recordOp]，账户初始余额恒 0）。 */
    private suspend fun newDebt(
        direction: DebtDirection,
        principal: Long = 0,
    ): String =
        (
            saveAccount(
                DebtAccountDraft(
                    counterparty = if (direction == DebtDirection.LEND) "小王" else "老李",
                    direction = direction,
                    principalCents = principal,
                    fundAccountId = if (principal > 0) "cash" else null,
                ),
            ) as AppResult.Success
        ).data

    /** 借贷账户余额：走仓库（= AccountDao 聚合口径），不是测试自己另算一套。 */
    private suspend fun debtBalance(debtId: String): Long = accountRepo.balanceOf(debtId)

    /** 资金账户当前余额（初始 10000）。 */
    private suspend fun cashBalance(): Long = accountRepo.balanceOf("cash")

    // ---------- 建账户 ----------

    @Test
    @DisplayName("建借贷账户：对方必填、本金为负拒绝、有本金必须选资金账户")
    fun saveAccountValidation() =
        runTest {
            val blank = saveAccount(DebtAccountDraft(counterparty = "  "))
            assertEquals(AppError.DebtCounterpartyRequired, (blank as AppResult.Failure).error)

            val negative = saveAccount(DebtAccountDraft(counterparty = "小王", principalCents = -1))
            assertEquals(AppError.AmountNotPositive, (negative as AppResult.Failure).error)

            val noFund = saveAccount(DebtAccountDraft(counterparty = "小王", principalCents = 100_00))
            assertEquals("请选择本金从哪个账户进出", (noFund as AppResult.Failure).error.message)
        }

    @Test
    @DisplayName("建「我借出」账户并带本金：账户余额 +1000、现金 −1000")
    fun createLendWithPrincipal() =
        runTest {
            val id = newDebt(DebtDirection.LEND, principal = 1_000_00)
            assertEquals(1_000_00, debtBalance(id))
            assertEquals(-1_000_00, cashBalance())

            val account = accountRepo.items[id]!!
            assertTrue(account.isLending)
            assertFalse(account.isBorrowing)
            assertEquals("小王", account.name)
        }

    @Test
    @DisplayName("建「我借入」账户并带本金：账户余额 −1000、现金 +1000")
    fun createBorrowWithPrincipal() =
        runTest {
            val id = newDebt(DebtDirection.BORROW, principal = 1_000_00)
            assertEquals(-1_000_00, debtBalance(id))
            assertEquals(1_000_00, cashBalance())
            assertTrue(accountRepo.items[id]!!.isBorrowing)
        }

    @Test
    @DisplayName("先建人、后记账：本金为 0 时不产生流水")
    fun createWithoutPrincipal() =
        runTest {
            val id = newDebt(DebtDirection.LEND)
            assertTrue(txnRepo.items.isEmpty())
            assertEquals(0L, debtBalance(id))
        }

    // ---------- 四操作 ----------

    @Test
    @DisplayName("借出 → 收款：待收回到 0，现金回补，流水不计入收支统计")
    fun lendThenCollect() =
        runTest {
            val id = newDebt(DebtDirection.LEND)
            val lend = recordOp(DebtOpDraft(DebtAction.LEND_OUT, id, "cash", 3_000_00))
            assertTrue(lend is AppResult.Success)
            assertEquals(3_000_00, debtBalance(id))
            assertEquals(-3_000_00, cashBalance())

            val collect = recordOp(DebtOpDraft(DebtAction.COLLECT, id, "cash", 3_000_00))
            assertTrue(collect is AppResult.Success)
            assertEquals(0L, debtBalance(id))

            val txn = txnRepo.items[(collect as AppResult.Success).data]!!
            assertEquals(TxnType.DEBT, txn.type)
            assertEquals(id, txn.accountId)
            assertEquals("cash", txn.toAccountId)
            assertTrue(txn.excludedFromStats, "借贷流水不能出现在收支统计里")
        }

    @Test
    @DisplayName("借入 → 还款：待还回到 0")
    fun borrowThenRepay() =
        runTest {
            val id = newDebt(DebtDirection.BORROW)
            recordOp(DebtOpDraft(DebtAction.BORROW_IN, id, "cash", 2_000_00))
            assertEquals(-2_000_00, debtBalance(id))

            recordOp(DebtOpDraft(DebtAction.REPAY, id, "cash", 2_000_00))
            assertEquals(0L, debtBalance(id))
            assertEquals(0L, cashBalance())
        }

    @Test
    @DisplayName("方向锁定：借出账户不能记借入/还款，借入账户不能记借出/收款")
    fun directionLocked() =
        runTest {
            val lend = newDebt(DebtDirection.LEND)
            val borrow = newDebt(DebtDirection.BORROW)

            val a = recordOp(DebtOpDraft(DebtAction.BORROW_IN, lend, "cash", 100_00))
            assertEquals("「小王」是「我借出」账户，只能记借出和收款", (a as AppResult.Failure).error.message)

            val b = recordOp(DebtOpDraft(DebtAction.COLLECT, borrow, "cash", 100_00))
            assertEquals("「老李」是「我借入」账户，只能记借入和还款", (b as AppResult.Failure).error.message)
        }

    @Test
    @DisplayName("收款不能超过待收、还款不能超过待还")
    fun overCollectAndOverRepay() =
        runTest {
            val lend = newDebt(DebtDirection.LEND, principal = 500_00)
            val over = recordOp(DebtOpDraft(DebtAction.COLLECT, lend, "cash", 500_01))
            assertTrue((over as AppResult.Failure).error.message!!.startsWith("收款金额不能超过待收"))

            val borrow = newDebt(DebtDirection.BORROW, principal = 500_00)
            val over2 = recordOp(DebtOpDraft(DebtAction.REPAY, borrow, "cash", 500_01))
            assertTrue((over2 as AppResult.Failure).error.message!!.startsWith("还款金额不能超过待还"))
        }

    @Test
    @DisplayName("资金账户不能是借贷账户本身，也不能是另一个借贷账户")
    fun fundAccountMustBeReal() =
        runTest {
            val lend = newDebt(DebtDirection.LEND)
            val other = newDebt(DebtDirection.BORROW)

            val self = recordOp(DebtOpDraft(DebtAction.LEND_OUT, lend, lend, 100_00))
            assertEquals("资金账户不能是借贷账户", (self as AppResult.Failure).error.message)

            val otherDebt = recordOp(DebtOpDraft(DebtAction.LEND_OUT, lend, other, 100_00))
            assertEquals("资金账户不能是借贷账户", (otherDebt as AppResult.Failure).error.message)
        }

    @Test
    @DisplayName("金额校验：0 与负数拒绝、超上限拒绝")
    fun amountValidation() =
        runTest {
            val id = newDebt(DebtDirection.LEND)
            assertEquals(AppError.AmountNotPositive, (recordOp(DebtOpDraft(DebtAction.LEND_OUT, id, "cash", 0)) as AppResult.Failure).error)
            assertEquals(
                AppError.AmountTooLarge,
                (recordOp(DebtOpDraft(DebtAction.LEND_OUT, id, "cash", Long.MAX_VALUE)) as AppResult.Failure).error,
            )
        }

    // ---------- 坏账 ----------

    @Test
    @DisplayName("坏账核销：待收清零、不产生资金侧流水")
    fun writeOffLend() =
        runTest {
            val id = newDebt(DebtDirection.LEND, principal = 3_000_00)
            assertTrue(writeOff(id) is AppResult.Success)

            assertEquals(0L, debtBalance(id))
            assertEquals(-3_000_00, cashBalance(), "核销不回补现金")

            val txn = txnRepo.items.values.single { it.debtAction == DebtAction.WRITE_OFF }
            assertNull(txn.toAccountId, "核销没有资金账户")
            assertTrue(txn.excludedFromStats)
        }

    @Test
    @DisplayName("借入方向不允许核销（否则净资产凭空虚增）")
    fun writeOffBorrowRejected() =
        runTest {
            val id = newDebt(DebtDirection.BORROW, principal = 1_000_00)
            val result = writeOff(id)
            assertEquals("只有「我借出」的账户可以做坏账核销", (result as AppResult.Failure).error.message)
        }

    @Test
    @DisplayName("没有待收时核销被拒")
    fun writeOffEmptyRejected() =
        runTest {
            val id = newDebt(DebtDirection.LEND)
            assertEquals("该账户没有待收金额", (writeOff(id) as AppResult.Failure).error.message)
        }

    // ---------- 流水形态 ----------

    @Test
    @DisplayName("借贷流水的备注默认是「动作：对方名」")
    fun defaultNote() =
        runTest {
            val id = newDebt(DebtDirection.LEND)
            val r = recordOp(DebtOpDraft(DebtAction.LEND_OUT, id, "cash", 100_00)) as AppResult.Success
            assertEquals("借出：小王", txnRepo.items[r.data]!!.note)
        }

    @Test
    @DisplayName("自定义备注被保留并截断到 200 字")
    fun customNoteTrimmed() =
        runTest {
            val id = newDebt(DebtDirection.LEND)
            val r =
                recordOp(DebtOpDraft(DebtAction.LEND_OUT, id, "cash", 100_00, note = "吃".repeat(300))) as AppResult.Success
            val txn: Txn = txnRepo.items[r.data]!!
            assertEquals(200, txn.note.length)
        }

    @Test
    @DisplayName("借贷账户不存在时返回 NotFound")
    fun missingDebtAccount() =
        runTest {
            assertEquals(
                AppError.NotFound,
                (recordOp(DebtOpDraft(DebtAction.LEND_OUT, "missing", "cash", 100_00)) as AppResult.Failure).error,
            )
        }
}
