package com.xxjz.ledger.domain.usecase

import com.xxjz.ledger.domain.model.Account
import com.xxjz.ledger.domain.model.AccountType
import com.xxjz.ledger.domain.repository.AccountRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * 净资产汇总（v1.8.0 口径，PRD 6.3 / D16）：
 *
 * - 总资产 = Σ现金余额 + Σ储蓄卡余额 + Σ(计入净资产的投资账户市值)
 * - 总负债 = Σ信用账户欠款 + Σ借贷净负债 + Σ现金/储蓄卡透支
 * - 净资产 = 总资产 − 总负债
 *
 * 三处刻意的处理：
 * 1. **投资账户按市值计，不叠加余额**。投资账户的「余额」是转入转出的净额，
 *    与市值是同一笔钱的两种表示，两者相加会翻倍；
 * 2. **现金/储蓄卡透支计入负债**。不按类型硬编码正负，防止「储蓄卡透支」被漏算（沿用 v1.7 的思路）；
 * 3. **隐藏账户不计入**，与列表可见性保持一致。
 */
data class AssetSummary(
    /** 现金余额（分），正数部分。 */
    val cash: Long = 0,
    /** 储蓄卡余额（分），正数部分。 */
    val savings: Long = 0,
    /** 计入净资产的投资市值（分）。 */
    val investMarket: Long = 0,
    /** 信用类欠款（分），正数表示欠多少。 */
    val creditDebt: Long = 0,
    /** 借贷净负债（分），正数表示净欠多少。 */
    val debtLiability: Long = 0,
    /** 现金/储蓄卡透支额（分），正数表示透支了多少，计入负债。 */
    val overdraft: Long = 0,
    /** 未计入净资产的投资市值（分），仅用于提示，不进总资产。 */
    val investExcluded: Long = 0,
) {
    val assets: Long get() = cash + savings + investMarket

    val liabilities: Long get() = creditDebt + debtLiability + overdraft

    val net: Long get() = assets - liabilities
}

/**
 * 净资产纯计算器：把「账户列表 → [AssetSummary]」这一步从 UseCase 里抽出来，
 * 让小组件、报表这类只需要算数、不需要仓库的地方直接复用同一套口径（避免口径漂移）。
 */
object NetAssetsCalculator {
    fun compute(accounts: List<Account>): AssetSummary {
        var cash = 0L
        var savings = 0L
        var investMarket = 0L
        var investExcluded = 0L
        var creditDebt = 0L
        var debtLiability = 0L
        var overdraft = 0L

        accounts.filter { !it.hidden }.forEach { account ->
            when (account.type) {
                AccountType.CASH, AccountType.SAVINGS -> {
                    if (account.balance >= 0) {
                        if (account.type == AccountType.CASH) cash += account.balance else savings += account.balance
                    } else {
                        overdraft += -account.balance
                    }
                }
                AccountType.CREDIT -> {
                    // 信用账户正常情况下是负余额（欠款）；出现正余额（多还了）按资产算更符合直觉
                    if (account.balance < 0) creditDebt += -account.balance else savings += account.balance
                }
                AccountType.DEBT -> {
                    // 借贷账户：正余额 = 净欠（借入 > 借出），负余额 = 净被欠
                    if (account.balance > 0) debtLiability += account.balance else savings += -account.balance
                }
                AccountType.INVEST -> {
                    if (account.includeInNetAssets) {
                        investMarket += account.marketValueCents
                    } else {
                        investExcluded += account.marketValueCents
                    }
                }
            }
        }

        return AssetSummary(
            cash = cash,
            savings = savings,
            investMarket = investMarket,
            creditDebt = creditDebt,
            debtLiability = debtLiability,
            overdraft = overdraft,
            investExcluded = investExcluded,
        )
    }

    /** 只要净资产一个数时的便捷入口。 */
    fun net(accounts: List<Account>): Long = compute(accounts).net
}

class GetAssetSummaryUseCase
    @Inject
    constructor(
        private val accountRepository: AccountRepository,
    ) {
        operator fun invoke(accounts: List<Account>): AssetSummary = NetAssetsCalculator.compute(accounts)

        suspend fun current(): AssetSummary = invoke(accountRepository.observeWithBalances().first())
    }
