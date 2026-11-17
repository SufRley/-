package com.xxjz.ledger.domain.usecase

import com.xxjz.ledger.domain.model.Account
import com.xxjz.ledger.domain.model.AccountType

/**
 * 资金账户口径（v1.9.5 第 3、11 条）。
 *
 * 三组用途、两组类型，**付款与转出不是同一组**——这是本版最容易写错的一处：
 *
 * | 用途 | 允许 | 说明 |
 * |---|---|---|
 * | 支出付款、借贷资金账户、报销到账 | 现金 / 储蓄卡 / 信用 | 从"活钱"里出 |
 * | 转账两端（转出 / 转入） | 现金 / 储蓄卡 / 信用 / 投资 | 投资账户的买入与赎回本身就是转账 |
 *
 * 投资账户为什么能转出却不能付款：它的"余额"是持仓里剩下的现金，
 * 买入（转出到投资）与赎回（从投资转出）都必须通，否则钱进不去也出不来；
 * 但它不是可随时花掉的活钱，拿它记"午饭 30 元"等于从股票账户里扣饭钱，账务上是错的。
 *
 * 为什么值得单独立一个对象：此前付款列表写成 `type != DEBT`，投资与借贷一起漏了进来；
 * 报销到账也复用了同一份过宽的集合。口径散在各页面时，改一处必漏一处。
 */
object FundPayable {
    /** 能"付出去"的账户：支出付款、借贷资金账户、报销到账。投资不在此列。 */
    val PAY_TYPES: Set<AccountType> = setOf(AccountType.CASH, AccountType.SAVINGS, AccountType.CREDIT)

    /** 能做转账两端的账户：在 [PAY_TYPES] 基础上加投资。 */
    val TRANSFER_TYPES: Set<AccountType> =
        setOf(AccountType.CASH, AccountType.SAVINGS, AccountType.CREDIT, AccountType.INVEST)

    /** 可作为付款账户：类型符合且未隐藏。 */
    fun canPay(account: Account?): Boolean = account != null && !account.hidden && account.type in PAY_TYPES

    /** 可作为转账两端：类型符合且未隐藏。 */
    fun canTransfer(account: Account?): Boolean = account != null && !account.hidden && account.type in TRANSFER_TYPES

    /** 付款账户候选，按 sortOrder 排（与账户页一致）。 */
    fun payableAccounts(accounts: List<Account>): List<Account> = pick(accounts, PAY_TYPES)

    /** 转账两端候选，含投资。 */
    fun transferAccounts(accounts: List<Account>): List<Account> = pick(accounts, TRANSFER_TYPES)

    private fun pick(
        accounts: List<Account>,
        types: Set<AccountType>,
    ): List<Account> = accounts.filter { !it.hidden && it.type in types }.sortedBy { it.sortOrder }

    /**
     * 报销到账的默认账户。
     *
     * 原支出的付款账户能用就用它——这是绝大多数情况，用户不用改；
     * 它被删掉、被隐藏，或本来就是投资账户（历史数据）时**不替用户挑一个**，返回 null。
     * 涉及钱的默认值不能猜：自动填一个用户没看过的账户，界面上也看不出「原来那张卡怎么没了」。
     */
    fun resolveReimburseTarget(
        accounts: List<Account>,
        originalAccountId: String?,
    ): Account? = payableAccounts(accounts).firstOrNull { it.id == originalAccountId }
}
