package com.xxjz.ledger.domain.usecase

import com.xxjz.ledger.core.common.AppError
import com.xxjz.ledger.core.common.AppResult
import com.xxjz.ledger.core.common.DateUtils
import com.xxjz.ledger.data.local.seed.SeedData
import com.xxjz.ledger.domain.model.Txn
import com.xxjz.ledger.domain.model.TxnType
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
 * - 报销收入统一落在预置的「报销」分类（[SeedData.REIMBURSE_CATEGORY_ID]），不复用用户分类，
 *   报表里可以单独看到「报销」这一项，不会被工资/副业污染；
 * - 撤销 = 删掉那笔收入 + 原账目回到待报销状态，两步在同一次调用里完成（AC-62）。
 */
class ReimburseTxnUseCase
    @Inject
    constructor(
        private val txnRepository: TxnRepository,
        private val categoryRepository: CategoryRepository,
    ) {
        /** 确认报销：[actualCents] 为实际到账金额，必须 1..原支出金额。 */
        suspend fun confirm(
            txnId: String,
            actualCents: Long,
            now: Long = System.currentTimeMillis(),
        ): AppResult<ReimburseResult> {
            val original = txnRepository.byId(txnId) ?: return AppResult.Failure(AppError.NotFound)
            if (original.type != TxnType.EXPENSE) return AppResult.Failure(AppError.Custom("只有支出可以报销"))
            if (original.isDeleted) return AppResult.Failure(AppError.Custom("该账目已删除"))
            if (!original.pendingReimbursement) return AppResult.Failure(AppError.Custom("该账目不在待报销状态"))
            if (actualCents <= 0L) return AppResult.Failure(AppError.AmountNotPositive)
            if (actualCents > original.amount) return AppResult.Failure(AppError.Custom("报销金额不能超过支出金额"))

            val income =
                Txn(
                    id = SeedData.newId("txn"),
                    type = TxnType.INCOME,
                    amount = actualCents,
                    categoryId = SeedData.REIMBURSE_CATEGORY_ID,
                    accountId = original.accountId,
                    dateTime = now,
                    dateKey = DateUtils.dateKeyOf(now),
                    note = "报销：${original.note}".take(200),
                    parentTxnId = original.id,
                    createdAt = now,
                    updatedAt = now,
                )
            txnRepository.insert(income)
            txnRepository.setReimbursed(original.id, actualCents, now, now, pending = false)
            categoryRepository.increaseUsage(SeedData.REIMBURSE_CATEGORY_ID)
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

        /** 待报销合计（分），用于首页胶囊。 */
        suspend fun pendingTotal(): Long = txnRepository.pendingReimbursement().sumOf { it.amount }
    }
