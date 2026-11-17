package com.xxjz.ledger.domain.usecase

import com.xxjz.ledger.core.common.AppError
import com.xxjz.ledger.core.common.AppResult
import com.xxjz.ledger.core.common.DateUtils
import com.xxjz.ledger.domain.model.Account
import com.xxjz.ledger.domain.model.AccountType
import com.xxjz.ledger.domain.model.FeeMode
import com.xxjz.ledger.domain.model.InstallmentScope
import com.xxjz.ledger.domain.model.Txn
import com.xxjz.ledger.domain.model.TxnType
import com.xxjz.ledger.testutil.FakeAccountRepository
import com.xxjz.ledger.testutil.FakeInstallmentRepository
import com.xxjz.ledger.testutil.FakeTxnRepository
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * 信用账户账单与分期用例（PRD 3.4 / 3.5 / 9.7 / 9.8 / 9.10）。
 *
 * 重点不是「能建分期」，而是**两个数字不能算错**：
 * 本期消费要含已分期的全额，本期应还只算未分期消费 + 计划本期应还。
 */
@DisplayName("信用账单")
class CreditBillTest {
    private val txnRepo = FakeTxnRepository()
    private val accountRepo = FakeAccountRepository().also { it.txns = txnRepo }
    private val planRepo = FakeInstallmentRepository()
    private val getBill = GetCreditBillUseCase(accountRepo, txnRepo, planRepo)
    private val createPlan = CreateInstallmentUseCase(accountRepo, txnRepo, planRepo)
    private val repay = RepayCreditUseCase(accountRepo, txnRepo)

    /** 账单日 15、还款日 20，与 PRD 9.2 的数例一致。 */
    private val credit =
        Account(
            id = "cr",
            name = "花呗",
            type = AccountType.CREDIT,
            cardLimit = 20_000_00,
            billDay = 15,
            repayDay = 20,
        )

    private val today = LocalDate.of(2026, 11, 14)

    @BeforeEach
    fun setUp() {
        accountRepo.items["cr"] = credit
        accountRepo.items["cash"] = Account(id = "cash", name = "现金", type = AccountType.CASH, initialBalance = 10_000_00)
        accountRepo.items["sav"] = Account(id = "sav", name = "储蓄卡", type = AccountType.SAVINGS, initialBalance = 10_000_00)
    }

    /** 账期 10/15 ~ 11/14 内的一笔消费。 */
    private fun spend(
        id: String,
        cents: Long,
        at: LocalDate = LocalDate.of(2026, 11, 1),
    ) {
        val ms = DateUtils.toMillis(at, LocalTime.NOON)
        txnRepo.put(
            Txn(
                id = id,
                type = TxnType.EXPENSE,
                amount = cents,
                categoryId = null,
                accountId = "cr",
                toAccountId = null,
                dateTime = ms,
                dateKey = DateUtils.dateKeyOf(ms),
                createdAt = ms,
                updatedAt = ms,
            ),
        )
    }

    // ---------- 9.1 / 9.2 账单口径 ----------

    @Test
    @DisplayName("9.1：信用账户给出本期应还、本期消费、还款日")
    fun billFields() =
        runTest {
            spend("t1", 1_000_00)
            spend("t2", 2_000_00)
            val bill = getBill.of("cr", today)!!

            assertEquals(3_000_00L, bill.periodSpendCents)
            assertEquals(3_000_00L, bill.dueCents)
            assertEquals("11/20", bill.repayAtText)
        }

    @Test
    @DisplayName("账期外的消费不算进本期")
    fun outOfCycleExcluded() =
        runTest {
            spend("in", 500_00, LocalDate.of(2026, 10, 15))
            spend("out", 900_00, LocalDate.of(2026, 10, 14)) // 上一账期
            val bill = getBill.of("cr", today)!!
            assertEquals(500_00L, bill.periodSpendCents)
        }

    @Test
    @DisplayName("非信用账户没有账单")
    fun nonCreditHasNoBill() =
        runTest {
            assertNull(getBill.of("cash", today))
        }

    // ---------- 9.4 / 9.5 建分期 ----------

    @Test
    @DisplayName("9.4：3000 元分 3 期、手续费 90 均摊 → 每期 1030")
    fun createAverage() =
        runTest {
            spend("t1", 3_000_00)
            val id =
                (
                    createPlan(
                        InstallmentDraft(
                            scope = InstallmentScope.TXN,
                            txnId = "t1",
                            accountId = "cr",
                            principalCents = 3_000_00,
                            feeCents = 90_00,
                            feeMode = FeeMode.AVERAGE,
                            periods = 3,
                            startBillKey = 202610,
                        ),
                    ) as AppResult.Success
                ).data
            val plan = planRepo.items[id]!!
            assertEquals(listOf(1_030_00L, 1_030_00L, 1_030_00L), plan.schedule().map { it.totalCents })
        }

    @Test
    @DisplayName("9.5：期数 0 / 1 / 61 都被拒，提示语固定")
    fun periodsRejected() =
        runTest {
            spend("t1", 1_000_00)
            listOf(0, 1, 61).forEach { n ->
                val r =
                    createPlan(
                        InstallmentDraft(
                            scope = InstallmentScope.TXN,
                            txnId = "t1",
                            accountId = "cr",
                            principalCents = 1_000_00,
                            periods = n,
                            startBillKey = 202610,
                        ),
                    )
                assertEquals("请输入 2~60 之间的期数", (r as AppResult.Failure).error.message, "期数 $n 应被拒")
            }
            assertTrue(planRepo.items.isEmpty())
        }

    @Test
    @DisplayName("只有信用账户能分期，非信用账户被拒")
    fun onlyCredit() =
        runTest {
            val r =
                createPlan(
                    InstallmentDraft(
                        scope = InstallmentScope.BILL,
                        accountId = "cash",
                        principalCents = 1_000_00,
                        periods = 3,
                        startBillKey = 202610,
                    ),
                )
            assertEquals("只有信用账户可以分期", (r as AppResult.Failure).error.message)
        }

    @Test
    @DisplayName("单笔分期必须指向本账户的消费，否则拒绝")
    fun txnMustBelongToAccount() =
        runTest {
            spend("t1", 1_000_00)
            val missing =
                createPlan(
                    InstallmentDraft(InstallmentScope.TXN, "nope", "cr", 1_000_00, periods = 3, startBillKey = 202610),
                )
            assertEquals(AppError.NotFound, (missing as AppResult.Failure).error)
        }

    // ---------- 9.7 分期后本期应还 ----------

    @Test
    @DisplayName("9.7：已分期消费不按原额计入应还，改按计划本期应还")
    fun splitExcludedFromDue() =
        runTest {
            spend("split", 3_000_00)
            spend("plain", 1_000_00)

            val billBefore = getBill.of("cr", today)!!
            assertEquals(4_000_00L, billBefore.periodSpendCents, "本期消费含全部消费")
            assertEquals(4_000_00L, billBefore.dueCents)

            // 把 3000 那笔分 3 期，起始账期就是当前账期
            createPlan(
                InstallmentDraft(
                    scope = InstallmentScope.TXN,
                    txnId = "split",
                    accountId = "cr",
                    principalCents = 3_000_00,
                    periods = 3,
                    startBillKey = billBefore.cycle.billKey,
                ),
            )

            val billAfter = getBill.of("cr", today)!!
            assertEquals(4_000_00L, billAfter.periodSpendCents, "本期消费不因分期而变化")
            assertEquals(2_000_00L, billAfter.dueCents, "未分期 1000 + 分期本期 1000")
            assertEquals(1, billAfter.activePlans.size)
        }

    @Test
    @DisplayName("9.8：分期不产生流水，净资产完全不变")
    fun splitKeepsNetWorth() =
        runTest {
            spend("t1", 3_000_00)
            val before = txnRepo.items.size
            val netBefore = NetAssetsCalculator.compute(accountRepo.items.values.toList()).net

            createPlan(
                InstallmentDraft(
                    scope = InstallmentScope.TXN,
                    txnId = "t1",
                    accountId = "cr",
                    principalCents = 3_000_00,
                    periods = 3,
                    startBillKey = 202610,
                ),
            )

            assertEquals(before, txnRepo.items.size, "分期不写流水")
            assertEquals(netBefore, NetAssetsCalculator.compute(accountRepo.items.values.toList()).net)
        }

    // ---------- 9.10 还清 ----------

    @Test
    @DisplayName("9.10：还满期数后分期标记为已结清，且不再进账单")
    fun settleAfterAllPeriods() =
        runTest {
            spend("t1", 3_000_00)
            val id =
                (
                    createPlan(
                        InstallmentDraft(
                            scope = InstallmentScope.TXN,
                            txnId = "t1",
                            accountId = "cr",
                            principalCents = 3_000_00,
                            periods = 3,
                            startBillKey = 202610,
                        ),
                    ) as AppResult.Success
                ).data

            val progress1 = GetInstallmentsUseCase(planRepo, txnRepo).of("cr").single()
            assertFalse(progress1.isSettled)
            assertEquals(0, progress1.paidPeriods)

            // 分三次还款，每期 1000
            repeat(3) {
                repay("cr", "cash", 1_000_00, installmentId = id)
            }

            val progress = GetInstallmentsUseCase(planRepo, txnRepo).of("cr").single()
            assertTrue(progress.isSettled, "还满 3 期即结清")
            assertEquals(3, progress.paidPeriods)
        }

    // ---------- 还款 ----------

    @Test
    @DisplayName("还款走资金账户：信用账户欠款减少、现金减少、不进收支统计")
    fun repayFromFund() =
        runTest {
            spend("t1", 1_000_00)
            val r = repay("cr", "cash", 600_00)
            assertTrue(r is AppResult.Success)

            val txn = txnRepo.items[(r as AppResult.Success).data]!!
            assertEquals(TxnType.EXPENSE, txn.type)
            assertEquals("cr", txn.accountId)
            assertEquals("cash", txn.toAccountId)
            assertTrue(txn.excludedFromStats, "还款是资金转移，不是消费")
        }

    @Test
    @DisplayName("还款资金账户不能是信用/借贷账户")
    fun repayFundRestricted() =
        runTest {
            accountRepo.items["cr2"] = Account(id = "cr2", name = "白条", type = AccountType.CREDIT)
            assertEquals("还款资金账户不能是信用或借贷账户", (repay("cr", "cr2", 100_00) as AppResult.Failure).error.message)
        }

    @Test
    @DisplayName("还款金额校验：0 与超上限被拒")
    fun repayAmountValidation() =
        runTest {
            assertEquals(AppError.AmountNotPositive, (repay("cr", "cash", 0) as AppResult.Failure).error)
            assertEquals(AppError.AmountTooLarge, (repay("cr", "cash", Long.MAX_VALUE) as AppResult.Failure).error)
        }

    @Test
    @DisplayName("查某笔流水的分期：只有 TXN 范围能对上")
    fun txnInstallmentLookup() =
        runTest {
            spend("t1", 1_000_00)
            createPlan(
                InstallmentDraft(InstallmentScope.TXN, "t1", "cr", 1_000_00, periods = 2, startBillKey = 202610),
            )
            val found = GetTxnInstallmentUseCase(planRepo).byTxn("t1")
            assertEquals(2, found?.periods)
            assertNull(GetTxnInstallmentUseCase(planRepo).byTxn("t2"))
        }
}
