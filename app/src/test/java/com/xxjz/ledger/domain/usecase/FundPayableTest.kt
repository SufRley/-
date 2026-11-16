package com.xxjz.ledger.domain.usecase

import com.xxjz.ledger.domain.model.Account
import com.xxjz.ledger.domain.model.AccountType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 资金账户口径（v1.9.5 第 3、11 条）。
 *
 * 这一组测试的核心只有一句话：**付款与转出不是同一组**。
 * 投资账户必须能转出、不能付款，任何一边写反都会在真机上表现为"钱进不去"或"拿股票账户买菜"。
 */
@DisplayName("资金账户口径")
class FundPayableTest {
    private fun account(
        id: String,
        type: AccountType,
        hidden: Boolean = false,
        sortOrder: Int = 0,
    ): Account = Account(id = id, name = id, type = type, hidden = hidden, sortOrder = sortOrder)

    private val all =
        listOf(
            account("cash", AccountType.CASH, sortOrder = 0),
            account("savings", AccountType.SAVINGS, sortOrder = 1),
            account("credit", AccountType.CREDIT, sortOrder = 2),
            account("invest", AccountType.INVEST, sortOrder = 3),
            account("debt", AccountType.DEBT, sortOrder = 4),
        )

    @Test
    @DisplayName("付款账户 = 现金 / 储蓄卡 / 信用，不含投资与借贷")
    fun payableExcludesInvest() {
        assertEquals(listOf("cash", "savings", "credit"), FundPayable.payableAccounts(all).map { it.id })
    }

    @Test
    @DisplayName("转账两端 = 现金 / 储蓄卡 / 信用 / 投资，不含借贷")
    fun transferIncludesInvest() {
        assertEquals(
            listOf("cash", "savings", "credit", "invest"),
            FundPayable.transferAccounts(all).map { it.id },
        )
    }

    @Test
    @DisplayName("投资账户可以转出，不能付款——两边结论必须相反")
    fun investCanTransferButCannotPay() {
        val invest = all.first { it.id == "invest" }
        assertTrue(FundPayable.canTransfer(invest))
        assertFalse(FundPayable.canPay(invest))
    }

    @Test
    @DisplayName("借贷账户两端都不行")
    fun debtAccountForNeither() {
        val debt = all.first { it.id == "debt" }
        assertFalse(FundPayable.canPay(debt))
        assertFalse(FundPayable.canTransfer(debt))
    }

    @Test
    @DisplayName("信用账户可以付款（信用卡直接刷卡）")
    fun creditCanPay() {
        assertTrue(FundPayable.canPay(all.first { it.id == "credit" }))
    }

    @Test
    @DisplayName("隐藏账户不出现在任何候选列表里")
    fun hiddenExcluded() {
        val list = all + account("ghost", AccountType.SAVINGS, hidden = true, sortOrder = 9)
        assertTrue(FundPayable.payableAccounts(list).none { it.id == "ghost" })
        assertTrue(FundPayable.transferAccounts(list).none { it.id == "ghost" })
    }

    @Test
    @DisplayName("候选列表按 sortOrder 排，与账户页顺序一致")
    fun sortedBySortOrder() {
        val unsorted =
            listOf(
                account("b", AccountType.SAVINGS, sortOrder = 5),
                account("a", AccountType.CASH, sortOrder = 1),
            )
        assertEquals(listOf("a", "b"), FundPayable.payableAccounts(unsorted).map { it.id })
    }

    @Test
    @DisplayName("null 账户一律不可用，不抛异常")
    fun nullAccountRejected() {
        assertFalse(FundPayable.canPay(null))
        assertFalse(FundPayable.canTransfer(null))
    }

    @Test
    @DisplayName("报销默认落到原支出账户")
    fun reimburseDefaultsToOriginal() {
        assertEquals("savings", FundPayable.resolveReimburseTarget(all, "savings")?.id)
    }

    @Test
    @DisplayName("原支出账户是投资时不预选，不替用户猜")
    fun reimburseNoGuessWhenOriginalUnusable() {
        assertNull(FundPayable.resolveReimburseTarget(all, "invest"))
        assertNull(FundPayable.resolveReimburseTarget(all, "not-exist"))
        assertNull(FundPayable.resolveReimburseTarget(all, null))
    }
}
