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

/**
 * 退款（AC-10 / AC-11）：为原支出生成一笔「退款收入」。
 *
 * 规则：
 * - 只有支出可退；退款金额 > 0；
 * - 累计退款（含本次）不得超过原支出金额；
 * - 退款是独立流水，通过 `refundOfTxnId` 关联原账目，原支出的「已退金额」由 SUM 实时计算；
 * - 退款收入不计入「收入」统计口径之外的特殊处理，但分类固定为预置的「退款」分类，便于报表下钻。
 */
class RefundTxnUseCase
    @Inject
    constructor(
        private val txnRepository: TxnRepository,
        private val categoryRepository: CategoryRepository,
    ) {
        suspend operator fun invoke(
            originalId: String,
            amountCents: Long,
            now: Long = System.currentTimeMillis(),
        ): AppResult<String> {
            val original = txnRepository.byId(originalId) ?: return AppResult.Failure(AppError.NotFound)
            if (original.type != TxnType.EXPENSE) return AppResult.Failure(AppError.Custom("只有支出可以退款"))
            if (original.isDeleted) return AppResult.Failure(AppError.Custom("该账目已删除"))
            if (amountCents <= 0) return AppResult.Failure(AppError.AmountNotPositive)

            val refunded = txnRepository.refundedTotal(originalId)
            if (refunded + amountCents > original.amount) {
                return AppResult.Failure(AppError.RefundExceedsOriginal)
            }

            val refund =
                Txn(
                    id = SeedData.newId("txn"),
                    type = TxnType.INCOME,
                    amount = amountCents,
                    categoryId = SeedData.REFUND_CATEGORY_ID,
                    accountId = original.accountId,
                    dateTime = now,
                    dateKey = DateUtils.dateKeyOf(now),
                    note = "退款：${original.note}".take(200),
                    refundOfTxnId = original.id,
                    createdAt = now,
                    updatedAt = now,
                )
            txnRepository.insert(refund)
            categoryRepository.increaseUsage(SeedData.REFUND_CATEGORY_ID)
            return AppResult.Success(refund.id)
        }
    }
