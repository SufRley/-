package com.xxjz.ledger.domain.usecase

import com.xxjz.ledger.core.common.AppError
import com.xxjz.ledger.core.common.AppResult
import com.xxjz.ledger.domain.repository.TxnRepository
import javax.inject.Inject

/**
 * 切换「不计入收支」（AC-12）。
 *
 * 统计口径的过滤写在 DAO 的聚合 SQL 里（`excludedFromStats = 0`），所以这里只要翻开关，
 * 首页月小计、分类报表、预算占用都会即时剔除，不需要各处再判断。
 */
class ToggleExcludeUseCase
    @Inject
    constructor(
        private val txnRepository: TxnRepository,
    ) {
        suspend operator fun invoke(
            txnId: String,
            now: Long = System.currentTimeMillis(),
        ): AppResult<Boolean> {
            val txn = txnRepository.byId(txnId) ?: return AppResult.Failure(AppError.NotFound)
            val next = !txn.excludedFromStats
            txnRepository.setExcluded(txnId, next, now)
            return AppResult.Success(next)
        }
    }
