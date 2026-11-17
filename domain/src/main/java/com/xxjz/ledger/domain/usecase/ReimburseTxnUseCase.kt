package com.xxjz.ledger.domain.usecase

import com.xxjz.ledger.core.common.AppError
import com.xxjz.ledger.core.common.AppResult
import com.xxjz.ledger.core.common.DateUtils
import com.xxjz.ledger.domain.model.Account
import com.xxjz.ledger.domain.model.Ids
import com.xxjz.ledger.domain.model.Txn
import com.xxjz.ledger.domain.model.TxnType
import com.xxjz.ledger.domain.repository.AccountRepository
import com.xxjz.ledger.domain.repository.CategoryRepository
import com.xxjz.ledger.domain.repository.TxnRepository
import javax.inject.Inject

/** 报销结果：生成的收入流水 + 自付差额（分，0 表示全额报销）。 */
data class ReimburseResult(
    val incomeTxnId: String,
    /** 实际报销金额（分）。 */
    val reimbursed: Long,
    /** 自己承担的部分（分）。 */
    val selfPaid: Long,
)

/**
 * 待报销（AC-59 ~ AC-62）。
 *
 * 口径：
 * - 原支出金额不变，只记「已报销金额」；净支出 = 原支出 - 报销收入 = 自付部分，
 *   所以差额不需要额外生成流水，报表自然收敛；
 * - 报销收入统一落在预置的「报销」分类（[Ids.REIMBURSE_CATEGORY_ID]），不复用用户分类，
 *   报表里可以单独看到「报销」这一项，不会被工资/副业污染；
 * - 撤销 = 删掉那笔收入 + 原账目回到待报销状态，两步在同一次调用里完成（AC-62）。
 */
class ReimburseTxnUseCase
    @Inject
    constructor(
        private val txnRepository: TxnRepository,
        private val categoryRepository: CategoryRepository,
        private val accountRepository: AccountRepository,
    ) {
        /**
         * 确认报销（v1.9.4 第 2 条扩展）。
         *
         * @param actualCents 实际到账金额，必须 1..原支出金额
         * @param accountId 到账账户；`null` 表示沿用原支出的付款账户（兼容老调用）
         * @param dateTime 这笔报销收入**发生在哪一天**（用户选的），写进 `dateTime` 与 `dateKey`，
         *   决定它落到明细的哪个分组、算进哪个月的报表与预算
         * @param now 这次操作的**系统时刻**，写进 `createdAt` 与原账的 `reimbursedAt`。
         *
         * 两个时间必须分开：用户把报销日期改成上月时，若两者混用，
         * 回收站排序与「最近操作」会一起跟着错乱。
         *
         * 原支出账目只由 [TxnRepository.setReimbursed] 写报销金额与时间，金额、日期、账户、分类一个都不动。
         */
        suspend fun confirm(
            txnId: String,
            actualCents: Long,
            accountId: String? = null,
            dateTime: Long = System.currentTimeMillis(),
            now: Long = System.currentTimeMillis(),
        ): AppResult<ReimburseResult> {
            val original = txnRepository.byId(txnId) ?: return AppResult.Failure(AppError.NotFound)
            validateOriginal(original, actualCents)?.let { return AppResult.Failure(it) }

            val targetId = accountId ?: original.accountId
            if (accountId != null) {
                val target = accountRepository.byId(accountId)
                validateTarget(target)?.let { return AppResult.Failure(it) }
            }

            val income =
                Txn(
                    id = Ids.newId("txn"),
                    type = TxnType.INCOME,
                    amount = actualCents,
                    categoryId = Ids.REIMBURSE_CATEGORY_ID,
                    accountId = targetId,
                    dateTime = dateTime,
                    dateKey = DateUtils.dateKeyOf(dateTime),
                    note = "报销：${original.note}".take(200),
                    parentTxnId = original.id,
                    createdAt = now,
                    updatedAt = now,
                )
            txnRepository.insert(income)
            txnRepository.setReimbursed(original.id, actualCents, now, now, pending = false)
            categoryRepository.increaseUsage(Ids.REIMBURSE_CATEGORY_ID)
            return AppResult.Success(
                ReimburseResult(
                    incomeTxnId = income.id,
                    reimbursed = actualCents,
                    selfPaid = original.amount - actualCents,
                ),
            )
        }

        /** 撤销报销：删掉自动生成的收入，原账目回到待报销。 */
        suspend fun undo(
            txnId: String,
            now: Long = System.currentTimeMillis(),
        ): AppResult<Unit> {
            val original = txnRepository.byId(txnId) ?: return AppResult.Failure(AppError.NotFound)
            if (original.reimbursedAt == null) return AppResult.Failure(AppError.Custom("该账目未报销"))
            txnRepository.reimbursementOf(original.id)?.let { txnRepository.hardDelete(it.id) }
            txnRepository.setReimbursed(original.id, 0, null, now, pending = true)
            return AppResult.Success(Unit)
        }

        /**
         * 原账目与金额的前置检查；返回 null 表示通过。
         *
         * 抽出来是为了让 [confirm] 的可读性不被一串提前返回切碎，
         * 也顺带避开 detekt 的 `ReturnCount`（这个函数原本已经 8 条返回，加上账户校验就超了）。
         */
        private fun validateOriginal(
            original: Txn,
            actualCents: Long,
        ): AppError? =
            when {
                original.type != TxnType.EXPENSE -> AppError.Custom("只有支出可以报销")
                original.isDeleted -> AppError.Custom("该账目已删除")
                !original.pendingReimbursement -> AppError.Custom("该账目不在待报销状态")
                actualCents <= 0L -> AppError.AmountNotPositive
                actualCents > original.amount -> AppError.Custom("报销金额不能超过支出金额")
                else -> null
            }

        /** 到账账户检查：必须是存在、未隐藏的资金账户。 */
        private fun validateTarget(target: Account?): AppError? =
            when {
                target == null -> AppError.Custom("到账账户不存在")
                target.type !in FundPayable.PAY_TYPES -> AppError.Custom("报销只能到账到现金 / 储蓄卡 / 信用账户")
                target.hidden -> AppError.Custom("到账账户不可用")
                else -> null
            }

        /** 待报销合计（分），用于首页胶囊。 */
        suspend fun pendingTotal(): Long = txnRepository.pendingReimbursement().sumOf { it.amount }
    }
