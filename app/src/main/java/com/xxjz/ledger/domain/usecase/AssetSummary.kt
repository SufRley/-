package com.xxjz.ledger.domain.usecase

import com.xxjz.ledger.domain.model.Account
import com.xxjz.ledger.domain.repository.AccountRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * 资产汇总（AC-19）：按 PRD 5.2，余额为正的部分计入总资产，为负的部分计入总负债。
 *
 * 信用卡欠款表现为负余额，因此自然落到负债侧；不做按账户类型硬编码，
 * 以免「储蓄卡透支」这类情况被漏算。
 */
data class AssetSummary(
    val assets: Long,
    val liabilities: Long,
) {
    val net: Long get() = assets - liabilities
}

class GetAssetSummaryUseCase
    @Inject
    constructor(
        private val accountRepository: AccountRepository,
    ) {
        operator fun invoke(accounts: List<Account>): AssetSummary {
            var assets = 0L
            var liabilities = 0L
            accounts.filter { !it.hidden }.forEach { account ->
                if (account.balance >= 0) assets += account.balance else liabilities += -account.balance
            }
            return AssetSummary(assets, liabilities)
        }

        suspend fun current(): AssetSummary = invoke(accountRepository.observeWithBalances().first())
    }
