package com.xxjz.ledger.domain.usecase

import com.xxjz.ledger.core.common.AppError
import com.xxjz.ledger.core.common.AppResult
import com.xxjz.ledger.domain.repository.AccountRepository
import javax.inject.Inject

/**
 * 删除账户（AC-22）：先软删除该账户下的流水（含转入转出），再删除账户本身。
 *
 * 至少保留一个账户，避免记账面板无账户可选。
 */
class DeleteAccountUseCase
    @Inject
    constructor(
        private val accountRepository: AccountRepository,
    ) {
        suspend operator fun invoke(
            id: String,
            now: Long = System.currentTimeMillis(),
        ): AppResult<Int> {
            // 只判存在性；账户名不参与删除逻辑
            accountRepository.byId(id) ?: return AppResult.Failure(AppError.NotFound)
            if (accountRepository.count() <= 1) return AppResult.Failure(AppError.Custom("至少保留一个账户"))
            val count = accountRepository.txnCount(id)
            accountRepository.deleteWithTxns(id, now)
            return AppResult.Success(count)
        }
    }
