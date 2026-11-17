package com.xxjz.ledger.feature.account

import com.xxjz.ledger.core.common.MoneyFormatter
import com.xxjz.ledger.domain.model.Account
import com.xxjz.ledger.domain.model.AccountType

/**
 * 账户卡片上的一行文字与一个数字（v1.9.6 第 4 条）。
 *
 * 提成纯函数而不是写在 Composable 里，是因为这几条规则全是「格式与符号」，
 * 用单测钉住比截屏比对可靠得多。
 */
object AccountDisplay {
    /**
     * 卡片右侧的主数字。
     *
     * 信用账户多还了会变成正数余额。以前原样显示 `¥200.00`，用户读成「这卡里还有 200」，
     * 实际是「还多了 200」。现在正数带 `+`，与欠款的 `-` 成对，符号本身就是解释；
     * 按用户要求不加「溢缴」这类字样。
     */
    fun amountText(account: Account): String {
        val money = MoneyFormatter.centsToYuan(account.balance)
        val signed = if (account.type == AccountType.CREDIT && account.balance > 0) "+$money" else money
        return "¥$signed"
    }

    /**
     * 卡片左下角的副标题。
     *
     * 信用账户给出「可用」：额度 + 余额（余额为负即欠款，所以欠 500、额度 1000 → 可用 500）。
     * 没设额度就只写「信用」——算不出来的时候不要编一个数出来。
     */
    fun subtitle(account: Account): String =
        when (account.type) {
            AccountType.DEBT ->
                when {
                    account.balance > 0 -> "借贷 · 待收"
                    account.balance < 0 -> "借贷 · 待还"
                    else -> "借贷 · 已结清"
                }
            AccountType.CREDIT -> availableCredit(account)?.let { "信用 · 可用 ¥${MoneyFormatter.centsToYuan(it)}" } ?: "信用"
            AccountType.CASH, AccountType.SAVINGS, AccountType.INVEST -> typeLabel(account.type)
        }

    /** 类型的中文名（编辑对话框的类型选择也用它，避免两处各写一套）。 */
    fun typeLabel(type: AccountType): String =
        when (type) {
            AccountType.CASH -> "现金"
            AccountType.SAVINGS -> "储蓄卡"
            AccountType.CREDIT -> "信用"
            AccountType.DEBT -> "借贷"
            AccountType.INVEST -> "投资"
        }

    /** 可用额度（分）；没设额度返回 null，由调用方决定不显示。 */
    fun availableCredit(account: Account): Long? {
        if (account.type != AccountType.CREDIT) return null
        val limit = account.cardLimit ?: return null
        if (limit <= 0L) return null
        return limit + account.balance
    }

    /** 数字是否该用错误色：欠款（负余额）标红。 */
    fun isNegative(account: Account): Boolean = account.balance < 0 && account.type in NEGATIVE_TYPES

    /** 只有信用与借贷的负数才是「欠」，现金/储蓄/投资的负数（记账错）不标红。 */
    private val NEGATIVE_TYPES = setOf(AccountType.CREDIT, AccountType.DEBT)
}
