package com.xxjz.ledger.domain.model

import java.time.LocalDate
import java.time.ZoneId

/**
 * 分期计划（v1.9.0 / PRD 3.2）。
 *
 * **每期金额不落库**，由 [InstallmentSchedule] 实时算；**已还期数也不落库**，
 * 由关联的还款流水条数实时统计。两处都只存一份真相，避免对不上。
 *
 * 分期本身不产生任何新流水——它只是把一笔已经记过的账重新安排还款时间，
 * 所以分期**不改变净资产**（PRD 9.8）。
 */
data class Installment(
    val id: String,
    val scope: InstallmentScope,
    /** `scope = TXN` 时指向那笔消费；`BILL` 时为空。 */
    val txnId: String?,
    /** 信用账户。 */
    val accountId: String,
    val principalCents: Long,
    /** 手续费总额（分），可为 0。 */
    val feeCents: Long = 0,
    val feeMode: FeeMode = FeeMode.AVERAGE,
    /** 期数，用户输入，2 ~ 60。 */
    val periods: Int,
    /** 起始账期 yyyyMM。 */
    val startBillKey: Int,
    val createdAt: Long = 0,
) {
    val totalCents: Long get() = principalCents + feeCents

    /** 每期计划（第 1 期下标为 0）。 */
    fun schedule(): List<InstallmentPeriod> = InstallmentSchedule.of(this)

    /**
     * 第 [billKey]（yyyyMM）账期下应还多少，不在覆盖范围内返回 0。
     *
     * 必须按「月差」算而不是直接相减：账期号是 yyyyMM，202612 到 202701 差 89 而不是 1。
     */
    fun amountInBill(billKey: Int): Long {
        val months = (billKey / 100 - startBillKey / 100) * 12 + (billKey % 100 - startBillKey % 100)
        if (months < 0) return 0L
        return schedule().getOrNull(months)?.totalCents ?: 0L
    }
}

/** 分期对象。 */
enum class InstallmentScope { TXN, BILL }

/** 手续费摊法。 */
enum class FeeMode {
    /** 均摊到每期，余数并入最后一期。 */
    AVERAGE,

    /** 首期一次收齐。 */
    FIRST,
}

/** 某一期的应还。 */
data class InstallmentPeriod(
    /** 第几期，从 1 开始。 */
    val index: Int,
    val principalCents: Long,
    val feeCents: Long,
) {
    val totalCents: Long get() = principalCents + feeCents
}

/**
 * 每期金额的纯计算（PRD 3.3）。
 *
 * 三条规则：
 * 1. 本金整除，**余数并入最后一期**——保证各期之和严格等于本金，不多不少一分钱；
 * 2. `AVERAGE` 手续费同样整除、余数并入最后一期；
 * 3. `FIRST` 手续费全放第一期，后续期只还本金。
 *
 * 例（PRD 9.4）：本金 3000 元分 3 期、手续费 90 元均摊 → 每期本金 1000、手续费 30，三期都是 1030。
 * 除不尽时（1000 元分 3 期）→ 333 / 333 / 334。
 */
object InstallmentSchedule {
    const val MIN_PERIODS = 2
    const val MAX_PERIODS = 60

    /** 期数合法区间（PRD 3.1：用户输入，不预设选项）。 */
    fun isValidPeriods(periods: Int): Boolean = periods in MIN_PERIODS..MAX_PERIODS

    fun of(plan: Installment): List<InstallmentPeriod> = of(plan.principalCents, plan.feeCents, plan.feeMode, plan.periods)

    fun of(
        principalCents: Long,
        feeCents: Long,
        feeMode: FeeMode,
        periods: Int,
    ): List<InstallmentPeriod> {
        if (periods <= 0) return emptyList()
        val perPrincipal = principalCents / periods
        val principalRemainder = principalCents % periods
        val perFee = if (feeMode == FeeMode.AVERAGE) feeCents / periods else 0
        val feeRemainder = if (feeMode == FeeMode.AVERAGE) feeCents % periods else 0

        return (1..periods).map { index ->
            val isLast = index == periods
            InstallmentPeriod(
                index = index,
                principalCents = perPrincipal + if (isLast) principalRemainder else 0,
                feeCents =
                    when (feeMode) {
                        FeeMode.FIRST -> if (index == 1) feeCents else 0
                        FeeMode.AVERAGE -> perFee + if (isLast) feeRemainder else 0
                    },
            )
        }
    }
}

/**
 * 账期（账单周期）计算（PRD 3.4，花呗口径）。
 *
 * 例：账单日 15 号、还款日 20 号
 * - 今天 11/14 → 本期 10/15 ~ 11/14，还款日 11/20
 * - 今天 11/15 → 本期 11/15 ~ 12/14，还款日 12/20
 */
data class BillCycle(
    /** 账期起始（当日 00:00，毫秒）。 */
    val startAt: Long,
    /** 账期结束（当日 23:59:59.999，毫秒）。 */
    val endAt: Long,
    /** 还款日当天 00:00（毫秒）。 */
    val repayAt: Long,
    /** 账期标识 yyyyMM，取起始月，用作 `installment.startBillKey`。 */
    val billKey: Int,
)

object BillCycleCalculator {
    /** 账期结束用「次日 00:00 减 1 毫秒」，比逐单位拼 23:59:59 少一类边界错误。 */
    fun of(
        today: LocalDate,
        billDay: Int,
        repayDay: Int,
        zone: ZoneId = ZoneId.systemDefault(),
    ): BillCycle {
        val bDay = billDay.coerceIn(1, 31)
        val startMonth = if (today.dayOfMonth >= bDay) today.withDayOfMonth(1) else today.minusMonths(1).withDayOfMonth(1)
        val start = atDaySafe(startMonth, bDay)
        val nextMonthStart = atDaySafe(startMonth.plusMonths(1), bDay)
        val end = nextMonthStart.minusDays(1)

        val repay = repayOnOrAfter(end, repayDay)

        return BillCycle(
            startAt = start.atStartOfDay(zone).toInstant().toEpochMilli(),
            endAt = end.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1,
            repayAt = repay.atStartOfDay(zone).toInstant().toEpochMilli(),
            billKey = startMonth.year * 100 + startMonth.monthValue,
        )
    }

    /**
     * 还款日：账期结束后**第一个到达的** [repayDay]。
     *
     * 账单日 15、还款日 20 → 账期结束在 14 号，还款日落在同月 20；
     * 若还款日早于账单日，同月那天已经过去，自然顺延到下月同一天。
     */
    private fun repayOnOrAfter(
        cycleEnd: LocalDate,
        repayDay: Int,
    ): LocalDate {
        val monthStart = cycleEnd.withDayOfMonth(1)
        val sameMonth = atDaySafe(monthStart, repayDay)
        return if (sameMonth.isAfter(cycleEnd)) sameMonth else atDaySafe(monthStart.plusMonths(1), repayDay)
    }

    /**
     * 取该月第 [day] 天；超出当月天数时收敛到月末。
     *
     * 账单日设成 31 号遇到 2 月不能凭空造日期，收敛到 28/29 号才是用户预期。
     */
    fun atDaySafe(
        monthStart: LocalDate,
        day: Int,
    ): LocalDate = monthStart.withDayOfMonth(day.coerceIn(1, monthStart.lengthOfMonth()))
}
