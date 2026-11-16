package com.xxjz.ledger.domain.usecase

import com.xxjz.ledger.core.common.AppError
import com.xxjz.ledger.core.common.AppResult
import com.xxjz.ledger.domain.model.AccountType
import com.xxjz.ledger.domain.model.FeeMode
import com.xxjz.ledger.domain.model.Installment
import com.xxjz.ledger.domain.model.InstallmentSchedule
import com.xxjz.ledger.domain.model.InstallmentScope
import com.xxjz.ledger.domain.model.TxnType
import com.xxjz.ledger.domain.repository.AccountRepository
import com.xxjz.ledger.domain.repository.InstallmentRepository
import com.xxjz.ledger.domain.repository.TxnRepository
import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject

/**
 * 对**本笔交易**发起分期（v1.9.5 第 9 条）。
 *
 * 与 v1.9.0 的区别：那次只是建了一条计划，原流水仍是全额，用户在明细里看到的还是「一笔 3000」；
 * 这次把原流水就地改成**本期应还**，剩下的期数交给 [RunInstallmentUseCase] 每月自动出账。
 *
 * 三条硬约束：
 * 1. 只有支出能分期（收入没有「分期还」这回事）；
 * 2. 付款账户必须是信用账户（储蓄卡分期在现实里不存在）；
 * 3. 已执行期数必须在 `0 .. 期数-1` 之间，否则这笔账要么已结清、要么越界。
 */
class StartInstallmentUseCase
    @Inject
    constructor(
        private val txnRepository: TxnRepository,
        private val accountRepository: AccountRepository,
        private val installmentRepository: InstallmentRepository,
    ) {
        suspend operator fun invoke(
            txnId: String,
            periods: Int,
            paidPeriods: Int = 0,
            feeCents: Long = 0,
            feeMode: FeeMode = FeeMode.AVERAGE,
            now: Long = System.currentTimeMillis(),
        ): AppResult<String> {
            val txn = txnRepository.byId(txnId) ?: return AppResult.Failure(AppError.NotFound)
            val account = txn.accountId?.let { accountRepository.byId(it) }
            validate(txn.type, txn.isDeleted, account?.type, periods, paidPeriods)
                ?.let { return AppResult.Failure(it) }

            val accountId = txn.accountId ?: return AppResult.Failure(AppError.AccountRequired)
            val schedule = InstallmentSchedule.of(txn.amount, feeCents, feeMode, periods)
            // 原流水落第 (paid+1) 期：用户已经还过 K 期，当前这期才是它真正占的金额
            val current = schedule[paidPeriods]
            val plan =
                Installment(
                    id = "ins_" + txn.id,
                    scope = InstallmentScope.TXN,
                    txnId = txn.id,
                    accountId = accountId,
                    principalCents = txn.amount,
                    feeCents = feeCents,
                    feeMode = feeMode,
                    periods = periods,
                    startBillKey = billKeyOf(txn.dateTime),
                    createdAt = now,
                    paidPeriods = paidPeriods,
                    nextRunAt = plusMonths(txn.dateTime, 1),
                )

            installmentRepository.upsert(plan)
            txnRepository.update(
                txn.copy(
                    amount = current.totalCents,
                    updatedAt = now,
                ),
            )
            return AppResult.Success(plan.id)
        }

        private fun validate(
            type: TxnType?,
            deleted: Boolean,
            accountType: AccountType?,
            periods: Int,
            paidPeriods: Int,
        ): AppError? =
            when {
                type != TxnType.EXPENSE -> AppError.Custom("只有支出可以分期")
                deleted -> AppError.NotFound
                accountType != AccountType.CREDIT -> AppError.Custom("只有信用卡支出可以分期")
                !InstallmentSchedule.isValidPeriods(periods) -> AppError.Custom("期数要在 2 ~ 60 之间")
                paidPeriods < 0 -> AppError.Custom("已执行期数不能为负")
                paidPeriods >= periods -> AppError.Custom("已执行期数必须小于总期数")
                else -> null
            }

        /** 账期标识取当月的 yyyyMM，用于账户页按账期展示。 */
        private fun billKeyOf(millis: Long): Int {
            val dt = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(millis), ZoneId.systemDefault())
            return dt.year * 100 + dt.monthValue
        }

        /**
         * 往后推一个月，保留日与时。
         *
         * 用 `LocalDateTime.plusMonths` 而不是 `+30 天`：每月天数不同，31 号 +1 月会收到 30 号，
         * 这正是用户预期的「下个月同一天」。
         */
        private fun plusMonths(
            millis: Long,
            months: Long,
        ): Long {
            val dt = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(millis), ZoneId.systemDefault())
            return dt.plusMonths(months).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }
    }
