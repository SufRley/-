package com.xxjz.ledger.feature.account

import com.xxjz.ledger.domain.model.Account
import com.xxjz.ledger.domain.model.AccountType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 账户卡片的数字与副标题（v1.9.6 第 4 条）。
 *
 * 信用账户的坑在于「余额的正负」：刷卡变负（欠款），还款往 0 涨，还多了变正。
 * 用户看到 `+200` 会读成「卡里有 200」，实际是「多还了 200」。符号 + 可用额度一起给，
 * 才不用心算。
 */
@DisplayName("账户卡片展示")
class AccountDisplayTest {
    private fun account(
        type: AccountType,
        balance: Long,
        limit: Long? = null,
    ) = Account(
        id = "acc",
        name = "A 信用账户",
        type = type,
        cardLimit = limit,
        balance = balance,
    )

    @Test
    fun `额度 1000 用了 500：主数字 -500，可用 500`() {
        val a = account(AccountType.CREDIT, balance = -50_000, limit = 100_000)

        assertEquals("¥-500.00", AccountDisplay.amountText(a))
        assertEquals("信用 · 可用 ¥500.00", AccountDisplay.subtitle(a))
        assertEquals(50_000L, AccountDisplay.availableCredit(a))
    }

    @Test
    fun `还款还清：主数字 0，可用回到满额`() {
        val a = account(AccountType.CREDIT, balance = 0, limit = 100_000)

        assertEquals("¥0.00", AccountDisplay.amountText(a))
        assertEquals("信用 · 可用 ¥1,000.00", AccountDisplay.subtitle(a))
    }

    @Test
    fun `多还 200：主数字带正号，可用 1200，不写溢缴`() {
        val a = account(AccountType.CREDIT, balance = 20_000, limit = 100_000)

        assertEquals("¥+200.00", AccountDisplay.amountText(a))
        assertEquals("信用 · 可用 ¥1,200.00", AccountDisplay.subtitle(a))
        assertFalse(AccountDisplay.subtitle(a).contains("溢缴"), "用户明确不要这个字样")
    }

    @Test
    fun `没设额度的信用账户只写信用，不显示可用`() {
        val a = account(AccountType.CREDIT, balance = -50_000, limit = null)

        assertEquals("¥-500.00", AccountDisplay.amountText(a))
        assertEquals("信用", AccountDisplay.subtitle(a))
        assertNull(AccountDisplay.availableCredit(a))
    }

    @Test
    fun `额度为 0 视同没设`() {
        val a = account(AccountType.CREDIT, balance = -100, limit = 0)

        assertEquals("信用", AccountDisplay.subtitle(a))
    }

    @Test
    fun `现金与储蓄卡显示不变`() {
        val cash = account(AccountType.CASH, balance = 12_345)
        val savings = account(AccountType.SAVINGS, balance = 12_345)

        assertEquals("¥123.45", AccountDisplay.amountText(cash))
        assertEquals("现金", AccountDisplay.subtitle(cash))
        assertEquals("¥123.45", AccountDisplay.amountText(savings))
        assertEquals("储蓄卡", AccountDisplay.subtitle(savings))
    }

    @Test
    fun `投资账户显示不变`() {
        val a = account(AccountType.INVEST, balance = 500_000)

        assertEquals("¥5,000.00", AccountDisplay.amountText(a))
        assertEquals("投资", AccountDisplay.subtitle(a))
    }

    @Test
    fun `借贷账户仍按待收待还已结清判定`() {
        assertEquals("借贷 · 待收", AccountDisplay.subtitle(account(AccountType.DEBT, balance = 100)))
        assertEquals("借贷 · 待还", AccountDisplay.subtitle(account(AccountType.DEBT, balance = -100)))
        assertEquals("借贷 · 已结清", AccountDisplay.subtitle(account(AccountType.DEBT, balance = 0)))
    }

    @Test
    fun `只有信用与借贷的欠款标红`() {
        assertTrue(AccountDisplay.isNegative(account(AccountType.CREDIT, balance = -1)))
        assertTrue(AccountDisplay.isNegative(account(AccountType.DEBT, balance = -1)))
        assertFalse(AccountDisplay.isNegative(account(AccountType.CASH, balance = -1)), "现金负数是记错账，不该当欠款标红")
        assertFalse(AccountDisplay.isNegative(account(AccountType.CREDIT, balance = 1)), "多还了不是欠款")
    }
}
