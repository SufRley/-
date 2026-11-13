package com.xxjz.ledger.domain.usecase

import com.xxjz.ledger.core.common.AppError
import com.xxjz.ledger.core.common.AppResult
import com.xxjz.ledger.core.common.DateUtils
import com.xxjz.ledger.data.local.seed.SeedData
import com.xxjz.ledger.domain.repository.CategoryRepository
import com.xxjz.ledger.domain.repository.TxnRepository
import javax.inject.Inject

/**
 * 复制一笔账目（AC-15）：保留类型/金额/分类/账户/备注，时间重置为当前，
 * 并清空退款关联、报销与删除状态，避免复制出一笔「假退款」。
 */
class DuplicateTxnUseCase
    @Inject
    constructor(
        private val txnRepository: TxnRepository,
        private val categoryRepository: CategoryRepository,
    ) {
        suspend operator fun invoke(
            sourceId: String,
            now: Long = System.currentTimeMillis(),
        ): AppResult<String> {
            val source = txnRepository.byId(sourceId) ?: return AppResult.Failure(AppError.NotFound)
            val copy =
                source.copy(
                    id = SeedData.newId("txn"),
                    dateTime = now,
                    dateKey = DateUtils.dateKeyOf(now),
                    refundOfTxnId = null,
                    parentTxnId = null,
                    pendingReimbursement = false,
                    reimbursedAmount = 0,
                    reimbursedAt = null,
                    deletedAt = null,
                    createdAt = now,
                    updatedAt = now,
                )
            txnRepository.insert(copy)
            copy.categoryId?.let { categoryRepository.increaseUsage(it) }
            return AppResult.Success(copy.id)
        }
    }
