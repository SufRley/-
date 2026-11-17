package com.xxjz.ledger.domain.usecase

import com.xxjz.ledger.core.common.DateUtils
import com.xxjz.ledger.domain.model.BillCycleCalculator
import com.xxjz.ledger.domain.model.FeeMode
import com.xxjz.ledger.domain.model.Installment
import com.xxjz.ledger.domain.model.InstallmentSchedule
import com.xxjz.ledger.domain.model.InstallmentScope
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * v1.9.0 分期纯计算（PRD 3.3 / 3.4）。
 *
 * 这块是整个分期体系的地基：金额算错一分钱，账单卡和「已还几期」就全错。
 * 因此把「每期金额」「账期区间」「还款日」三件事单独锁死。
 */
@DisplayName("分期计算")
class InstallmentCalcTest {
    private fun plan(
        principal: Long = 3_000_00,
        fee: Long = 0,
        mode: FeeMode = FeeMode.AVERAGE,
        periods: Int = 3,
        startBillKey: Int = 202611,
    ) = Installment(
        id = "p1",
        scope = InstallmentScope.TXN,
        txnId = "t1",
        accountId = "credit",
        principalCents = principal,
        feeCents = fee,
        feeMode = mode,
        periods = periods,
        startBillKey = startBillKey,
    )

    // ---------- 每期金额（PRD 9.4 / 9.6） ----------

    @Test
    @DisplayName("9.4：本金 3000 分 3 期、手续费 90 均摊 → 每期 1030")
    fun averageFee() {
        val periods = InstallmentSchedule.of(plan(fee = 90_00))
        assertEquals(3, periods.size)
        assertEquals(listOf(1_030_00L, 1_030_00L, 1_030_00L), periods.map { it.totalCents })
        assertEquals(3_090_00L, periods.sumOf { it.totalCents }, "各期之和 = 本金 + 手续费，不差分厘")
    }

    @Test
    @DisplayName("9.6：首期收齐 → 首期 = 均摊本金 + 全部手续费，后续期无手续费")
    fun firstPeriodFee() {
        val periods = InstallmentSchedule.of(plan(fee = 90_00, mode = FeeMode.FIRST))
        assertEquals(listOf(1_090_00L, 1_000_00L, 1_000_00L), periods.map { it.totalCents })
        assertEquals(0L, periods[1].feeCents)
        assertEquals(0L, periods[2].feeCents)
    }

    @Test
    @DisplayName("本金除不尽：余数并入最后一期，各期之和严格等于本金")
    fun remainderToLastPeriod() {
        val periods = InstallmentSchedule.of(plan(principal = 1_000_00, fee = 0, periods = 3))
        assertEquals(listOf(333_33L, 333_33L, 333_34L), periods.map { it.principalCents })
        assertEquals(1_000_00L, periods.sumOf { it.principalCents })
    }

    @Test
    @DisplayName("手续费除不尽：余数也并入最后一期")
    fun feeRemainderToLastPeriod() {
        // 100 元分 3 期：每期 33.33，余 1 分并入最后一期 → 33.33 / 33.33 / 33.34
        val periods = InstallmentSchedule.of(plan(principal = 3_000_00, fee = 100_00, periods = 3))
        assertEquals(listOf(33_33L, 33_33L, 33_34L), periods.map { it.feeCents })
        assertEquals(100_00L, periods.sumOf { it.feeCents })
        assertEquals(3_100_00L, periods.sumOf { it.totalCents })
    }

    @Test
    @DisplayName("零手续费 / 两期 / 六十期都能算，且总和守恒")
    fun edgePeriods() {
        listOf(2, 7, 60).forEach { n ->
            val p = InstallmentSchedule.of(plan(principal = 1_234_56, fee = 0, periods = n))
            assertEquals(n, p.size)
            assertEquals(1_234_56L, p.sumOf { it.principalCents }, "$n 期本金必须守恒")
        }
    }

    @Test
    @DisplayName("9.5：期数 2~60 之外一律非法")
    fun periodsValidation() {
        assertFalse(InstallmentSchedule.isValidPeriods(0))
        assertFalse(InstallmentSchedule.isValidPeriods(1))
        assertFalse(InstallmentSchedule.isValidPeriods(61))
        assertFalse(InstallmentSchedule.isValidPeriods(-3))
        assertTrue(InstallmentSchedule.isValidPeriods(2))
        assertTrue(InstallmentSchedule.isValidPeriods(60))
    }

    @Test
    @DisplayName("分期不产生新钱：总应还 = 本金 + 手续费")
    fun totalInvariant() {
        val p = plan(principal = 5_000_00, fee = 120_00, mode = FeeMode.AVERAGE, periods = 12)
        assertEquals(5_120_00L, p.totalCents)
        assertEquals(5_120_00L, p.schedule().sumOf { it.totalCents })
    }

    // ---------- 账期（PRD 9.2 / 9.3） ----------

    private fun text(ms: Long): String = DateUtils.formatDate(ms.toLocalDate())

    @Test
    @DisplayName("9.2：账单日 15、今天 11/14 → 账期 10/15~11/14，还款日 11/20")
    fun cycleBeforeBillDay() {
        val c = BillCycleCalculator.of(LocalDate.of(2026, 11, 14), billDay = 15, repayDay = 20)
        assertEquals("2026-10-15", text(c.startAt))
        assertEquals("2026-11-14", text(c.endAt))
        assertEquals("2026-11-20", text(c.repayAt))
        assertEquals(202610, c.billKey)
    }

    @Test
    @DisplayName("9.3：今天 11/15 → 自动切到 11/15~12/14，还款日 12/20")
    fun cycleOnBillDay() {
        val c = BillCycleCalculator.of(LocalDate.of(2026, 11, 15), billDay = 15, repayDay = 20)
        assertEquals("2026-11-15", text(c.startAt))
        assertEquals("2026-12-14", text(c.endAt))
        assertEquals("2026-12-20", text(c.repayAt))
        assertEquals(202611, c.billKey)
    }

    @Test
    @DisplayName("账期结束是当日最后一毫秒，月初第一秒属于下一个账期")
    fun cycleBoundaries() {
        val c = BillCycleCalculator.of(LocalDate.of(2026, 11, 20), billDay = 15, repayDay = 20)
        val nextStart = BillCycleCalculator.of(LocalDate.of(2026, 12, 15), billDay = 15, repayDay = 20)
        assertEquals(nextStart.startAt, c.endAt + 1, "上期结束与下期开始严丝合缝，不漏也不重")
    }

    @Test
    @DisplayName("还款日早于账单日时顺延到下月")
    fun repayBeforeBillDay() {
        val c = BillCycleCalculator.of(LocalDate.of(2026, 11, 20), billDay = 25, repayDay = 5)
        assertEquals("2026-11-24", text(c.endAt))
        assertEquals("2026-12-05", text(c.repayAt))
    }

    @Test
    @DisplayName("账单日 31 号遇到 2 月收敛到月末，不造出不存在的日期")
    fun billDayClampsToMonthEnd() {
        val c = BillCycleCalculator.of(LocalDate.of(2026, 3, 1), billDay = 31, repayDay = 31)
        // 3/1 < 31 号 → 账期从上月（2 月）开始，2 月没有 31 号，收敛到 28 号
        assertEquals("2026-02-28", text(c.startAt))
        assertEquals(202602, c.billKey)
    }

    // ---------- 账期内应还 ----------

    @Test
    @DisplayName("某个账期的应还金额：只有落在覆盖区间内才非零")
    fun amountInBill() {
        val p = plan(principal = 3_000_00, fee = 0, periods = 3, startBillKey = 202611)
        assertEquals(1_000_00L, p.amountInBill(202611))
        assertEquals(1_000_00L, p.amountInBill(202612))
        assertEquals(1_000_00L, p.amountInBill(202701))
        assertEquals(0L, p.amountInBill(202610), "起始账期之前不算")
        assertEquals(0L, p.amountInBill(202702), "分期结束后不算")
    }
}
