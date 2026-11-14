package com.xxjz.ledger.domain.usecase

import com.xxjz.ledger.core.common.AppError
import com.xxjz.ledger.core.common.AppResult
import com.xxjz.ledger.core.common.DateUtils
import com.xxjz.ledger.core.common.MoneyFormatter
import com.xxjz.ledger.data.local.seed.SeedData
import com.xxjz.ledger.domain.model.Account
import com.xxjz.ledger.domain.model.AccountType
import com.xxjz.ledger.domain.model.BillCycle
import com.xxjz.ledger.domain.model.BillCycleCalculator
import com.xxjz.ledger.domain.model.FeeMode
import com.xxjz.ledger.domain.model.Installment
import com.xxjz.ledger.domain.model.InstallmentPeriod
import com.xxjz.ledger.domain.model.InstallmentSchedule
import com.xxjz.ledger.domain.model.InstallmentScope
import com.xxjz.ledger.domain.model.Txn
import com.xxjz.ledger.domain.model.TxnType
import com.xxjz.ledger.domain.repository.AccountRepository
import com.xxjz.ledger.domain.repository.InstallmentRepository
import com.xxjz.ledger.domain.repository.TxnRepository
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/** 信用账户的一张账单（v1.9.0 / PRD 3.4）。 */
data class CreditBill(
    val accountId: String,
    val cycle: BillCycle,
    /** 账期区间内全部消费之和（含已分期的全额）——回答「这个月花了多少」。 */
    val periodSpendCents: Long = 0,
    /** 本期应还 = 未分期消费 + 各分期计划本期应还的期数金额。 */
    val dueCents: Long = 0,
    /** 本期涉及的未结清分期。 */
    val activePlans: List<InstallmentProgress> = emptyList(),
) {
    /** 「11/20」形式的还款日，账单卡第二行用。 */
    val repayAtText: String
        get() = cycle.repayAt.toLocalDate().let { "${it.monthValue}/${it.dayOfMonth}" }
}

/** 一个分期的进度。 */
data class InstallmentProgress(
    val plan: Installment,
    /** 已还期数（由关联还款流水条数实时统计，不落库）。 */
    val paidPeriods: Int,
    /** 已还金额（分）。 */
    val paidCents: Long = 0,
) {
    val periods: Int get() = plan.periods
    val isSettled: Boolean get() = paidPeriods >= plan.periods
    val schedule: List<InstallmentPeriod> get() = plan.schedule()

    /** 每期金额文案，如「¥1,030.00 × 3 期」。 */
    val perPeriodText: String
        get() {
            val first = schedule.firstOrNull()?.totalCents ?: 0
            return "${MoneyFormatter.centsToYuan(first, showSymbol = true)} × $periods 期"
        }
}

/**
 * 查信用账户本期账单（PRD 3.4）。
 *
 * 「本期消费」和「本期应还」是两个数字，必须都算：
 * 前者回答花多少，后者回答该还多少。已分期的消费由分期计划接管，
 * 不再按原额计入应还——否则会重复算一次。
 */
class GetCreditBillUseCase
    @Inject
    constructor(
        private val accountRepository: AccountRepository,
        private val txnRepository: TxnRepository,
        private val installmentRepository: InstallmentRepository,
    ) {
        suspend fun of(
            accountId: String,
            today: LocalDate = LocalDate.now(),
        ): CreditBill? {
            val account = accountRepository.byId(accountId) ?: return null
            if (account.type != AccountType.CREDIT) return null
            return compute(account, today)
        }

        /** 账户对象已在手时直接用，避免再查一次库。 */
        suspend fun compute(
            account: Account,
            today: LocalDate = LocalDate.now(),
        ): CreditBill {
            val cycle = BillCycleCalculator.of(today, account.billDay ?: 1, account.repayDay ?: 20)
            val txns =
                txnRepository
                    .listInRange(cycle.startAt, cycle.endAt)
                    .filter { !it.isDeleted && it.type == TxnType.EXPENSE && it.accountId == account.id }

            val periodSpend = txns.sumOf { it.amount }

            val plans = installmentRepository.observeByAccount(account.id).first()
            val progresses = plans.map { plan -> progressOf(plan) }

            // 已分期的单笔消费不按原额计入应还
            val installmentedTxnIds = plans.filter { it.scope == InstallmentScope.TXN }.mapNotNull { it.txnId }.toSet()
            val plainSpend = txns.filter { it.id !in installmentedTxnIds }.sumOf { it.amount }

            val planDue =
                plans.sumOf { plan ->
                    // 整期账单分期会覆盖本期，且它本身就是把整期挪走，所以只算计划本身的应还
                    plan.amountInBill(cycle.billKey)
                }

            return CreditBill(
                accountId = account.id,
                cycle = cycle,
                periodSpendCents = periodSpend,
                dueCents = plainSpend + planDue,
                activePlans = progresses.filter { !it.isSettled },
            )
        }

        private suspend fun progressOf(plan: Installment): InstallmentProgress {
            val paid =
                txnRepository
                    .listInRange(0L, Long.MAX_VALUE)
                    .filter { it.installmentId == plan.id && !it.isDeleted }
            return InstallmentProgress(
                plan = plan,
                paidPeriods = paid.size,
                paidCents = paid.sumOf { it.amount },
            )
        }
    }

/** 建分期计划的草稿（PRD 3.1）。 */
data class InstallmentDraft(
    val scope: InstallmentScope,
    /** `scope = TXN` 时必填。 */
    val txnId: String? = null,
    val accountId: String,
    val principalCents: Long,
    val feeCents: Long = 0,
    val feeMode: FeeMode = FeeMode.AVERAGE,
    /** 期数，用户输入，2 ~ 60。 */
    val periods: Int,
    /** 起始账期 yyyyMM，默认当前账期。 */
    val startBillKey: Int,
)

/**
 * 建分期计划（PRD 3.1 / 3.3）。
 *
 * 只写 `installment` 表，**不产生任何流水**：分期不产生新钱，净资产完全不变（PRD 9.8）。
 */
class CreateInstallmentUseCase
    @Inject
    constructor(
        private val accountRepository: AccountRepository,
        private val txnRepository: TxnRepository,
        private val installmentRepository: InstallmentRepository,
    ) {
        suspend operator fun invoke(draft: InstallmentDraft): AppResult<String> {
            validateAmounts(draft)?.let { return AppResult.Failure(it) }

            val account = accountRepository.byId(draft.accountId) ?: return AppResult.Failure(AppError.NotFound)
            if (account.type != AccountType.CREDIT) {
                return AppResult.Failure(AppError.Custom("只有信用账户可以分期"))
            }
            if (draft.scope == InstallmentScope.TXN) {
                validateTxn(draft, account)?.let { return AppResult.Failure(it) }
            }

            val plan =
                Installment(
                    id = SeedData.newId("inst"),
                    scope = draft.scope,
                    txnId = if (draft.scope == InstallmentScope.TXN) draft.txnId else null,
                    accountId = account.id,
                    principalCents = draft.principalCents,
                    feeCents = draft.feeCents,
                    feeMode = draft.feeMode,
                    periods = draft.periods,
                    startBillKey = draft.startBillKey,
                    createdAt = System.currentTimeMillis(),
                )
            installmentRepository.upsert(plan)
            return AppResult.Success(plan.id)
        }

        /** 金额与期数的纯校验，先跑，省得为明显错误去查库。 */
        private fun validateAmounts(draft: InstallmentDraft): AppError? =
            when {
                !InstallmentSchedule.isValidPeriods(draft.periods) ->
                    AppError.Custom("请输入 ${InstallmentSchedule.MIN_PERIODS}~${InstallmentSchedule.MAX_PERIODS} 之间的期数")
                draft.principalCents <= 0L -> AppError.AmountNotPositive
                draft.principalCents > MoneyFormatter.MAX_CENTS -> AppError.AmountTooLarge
                draft.feeCents < 0L -> AppError.Custom("手续费不能为负")
                draft.feeCents > MoneyFormatter.MAX_CENTS -> AppError.AmountTooLarge
                else -> null
            }

        /** 单笔分期只能指向本账户、本期、未删除的消费。 */
        private suspend fun validateTxn(
            draft: InstallmentDraft,
            account: Account,
        ): AppError? {
            val txn = draft.txnId?.let { txnRepository.byId(it) } ?: return AppError.NotFound
            if (txn.type != TxnType.EXPENSE || txn.accountId != account.id || txn.isDeleted) {
                return AppError.Custom("只能对本期消费分期")
            }
            return null
        }
    }

/** 查某个信用账户全部分期进度（信用账户详情页、「已分 N 期」标签点开用）。 */
class GetInstallmentsUseCase
    @Inject
    constructor(
        private val installmentRepository: InstallmentRepository,
        private val txnRepository: TxnRepository,
    ) {
        suspend fun of(accountId: String): List<InstallmentProgress> {
            val all = txnRepository.listInRange(0L, Long.MAX_VALUE).filter { it.installmentId != null && !it.isDeleted }
            return installmentRepository.listByAccount(accountId).map { plan ->
                val paid = all.filter { it.installmentId == plan.id }
                InstallmentProgress(plan = plan, paidPeriods = paid.size, paidCents = paid.sumOf { it.amount })
            }
        }
    }

/** 某笔流水是否已被分期（流水列表打「已分 N 期」标签用）。 */
class GetTxnInstallmentUseCase
    @Inject
    constructor(
        private val installmentRepository: InstallmentRepository,
    ) {
        /** 返回该笔消费的分期；没有则 null。 */
        suspend fun byTxn(txnId: String): Installment? =
            installmentRepository
                .observeAll()
                .first()
                .firstOrNull { it.scope == InstallmentScope.TXN && it.txnId == txnId }
    }

/**
 * 记一次信用账户还款（PRD 3.5 「还款」按钮）。
 *
 * 生成一笔 `EXPENSE` 到信用账户的流水：信用账户余额减少（欠款变少），资金账户减少。
 * 若指定了 [installmentId]，同时在流水上打标——**已还期数就靠这个标记统计**，
 * 不在分期表里另存一个计数器。
 */
class RepayCreditUseCase
    @Inject
    constructor(
        private val accountRepository: AccountRepository,
        private val txnRepository: TxnRepository,
    ) {
        suspend operator fun invoke(
            accountId: String,
            fundAccountId: String,
            amountCents: Long,
            installmentId: String? = null,
            at: Long = System.currentTimeMillis(),
            note: String = "信用卡还款",
        ): AppResult<String> {
            if (amountCents <= 0L) return AppResult.Failure(AppError.AmountNotPositive)
            if (amountCents > MoneyFormatter.MAX_CENTS) return AppResult.Failure(AppError.AmountTooLarge)

            val account = accountRepository.byId(accountId) ?: return AppResult.Failure(AppError.NotFound)
            if (account.type != AccountType.CREDIT) return AppResult.Failure(AppError.Custom("该账户不是信用账户"))

            val fund = accountRepository.byId(fundAccountId) ?: return AppResult.Failure(AppError.AccountRequired)
            if (fund.type == AccountType.CREDIT || fund.type == AccountType.DEBT) {
                return AppResult.Failure(AppError.Custom("还款资金账户不能是信用或借贷账户"))
            }

            val now = System.currentTimeMillis()
            val txn =
                Txn(
                    id = SeedData.newId("txn"),
                    type = TxnType.EXPENSE,
                    amount = amountCents,
                    categoryId = null,
                    accountId = account.id,
                    toAccountId = fund.id,
                    dateTime = at,
                    dateKey = DateUtils.dateKeyOf(at),
                    note = note.take(200),
                    // 还款是资金账户 → 信用账户的转移，不是消费，不进收支统计
                    excludedFromStats = true,
                    installmentId = installmentId,
                    createdAt = now,
                    updatedAt = now,
                )
            txnRepository.insert(txn)
            return AppResult.Success(txn.id)
        }
    }

/** 本地日期 ↔ 毫秒，账单周期要用。 */
internal fun LocalDate.toStartOfDayMillis(zone: ZoneId = ZoneId.systemDefault()): Long = atStartOfDay(zone).toInstant().toEpochMilli()

internal fun LocalDate.toEndOfDayMillis(zone: ZoneId = ZoneId.systemDefault()): Long =
    plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1

internal fun Long.toLocalDate(zone: ZoneId = ZoneId.systemDefault()): LocalDate = Instant.ofEpochMilli(this).atZone(zone).toLocalDate()

/** 让 `combine` 有个不歧义的两参版本（避免与 kotlinx 的三参重载冲突）。 */
internal fun <A, B, R> combinePair(
    a: kotlinx.coroutines.flow.Flow<A>,
    b: kotlinx.coroutines.flow.Flow<B>,
    transform: suspend (A, B) -> R,
): kotlinx.coroutines.flow.Flow<R> = a.combine(b) { x, y -> transform(x, y) }
