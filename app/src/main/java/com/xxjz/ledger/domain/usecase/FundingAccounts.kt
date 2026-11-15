package com.xxjz.ledger.domain.usecase

import com.xxjz.ledger.domain.model.Account
import com.xxjz.ledger.domain.model.AccountType

/**
 * 资金账户口径（v1.9.4 第 2 条）。
 *
 * 需求方原话「仅资金账户」，最终确认口径为「除信用和借贷之外都算」——现金、储蓄卡、投资。
 *
 * - 信用账户收到报销款会变成「多还款」，语义上应是「还信用卡」而不是一笔收入；
 * - 借贷账户是「某人欠我」，收款意味着对方欠款减少，与报销无关；
 * - 投资账户的现金余额能进账，所以放进来。
 *
 * 抽成对象而不是散在 UI 里，是为了让「哪些账户能收钱」只有一处定义：
 * 对话框列表、默认值兜底、Use Case 校验三处都引用同一份判断，不会各写一遍再对不上。
 */
object FundingAccounts {
    /** 能收报销款的账户类型。 */
    val TYPES: Set<AccountType> = setOf(AccountType.CASH, AccountType.SAVINGS, AccountType.INVEST)

    /** 单账户是否可以作为资金账户：类型符合且未隐藏。 */
    fun isFunding(account: Account?): Boolean = account != null && account.type in TYPES && !account.hidden

    /** 可用于收款的账户列表，按 sortOrder 排（与账户页一致）。 */
    fun pickable(accounts: List<Account>): List<Account> = accounts.filter { isFunding(it) }.sortedBy { it.sortOrder }

    /**
     * 选定到账账户。
     *
     * 原支出的付款账户能用就用它——这是绝大多数情况，用户不用改；
     * 它被删掉或被隐藏时**不替用户挑一个**，返回 null 由调用方提示「请选择到账账户」。
     * 涉及钱的默认值不能猜：自动填一个用户没看过的账户，界面上也看不出「原来那张卡怎么没了」。
     */
    fun resolveDefault(
        accounts: List<Account>,
        originalAccountId: String?,
    ): Account? {
        val pickable = pickable(accounts)
        return pickable.firstOrNull { it.id == originalAccountId }
    }
}
