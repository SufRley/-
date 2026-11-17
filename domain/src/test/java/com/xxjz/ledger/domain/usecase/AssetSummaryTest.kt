package com.xxjz.ledger.domain.usecase

import com.xxjz.ledger.domain.model.Account
import com.xxjz.ledger.domain.model.AccountType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * v1.8.0 净资产口径（PRD 6.3 / D16）。
 *
 * 覆盖三件最容易写错的事：
 * 1. 投资账户按市值计，**不叠加余额**（否则同一笔钱算两次）；
 * 2. 现金/储蓄卡透支要落到负债，不能被「类型是资产」掩盖；
 * 3. 关闭「计入净资产」的投资账户市值不进总资产。
 */
@DisplayName("净资产口径")
class AssetSummaryTest {
    private fun cash(
        id: String,
        balance: Long,
        hidden: Boolean = false,
    ) = Account(id = id, name = id, type = AccountType.CASH, balance = balance, hidden = hidden)

    private fun savings(
        id: String,
        balance: Long,
    ) = Account(id = id, name = id, type = AccountType.SAVINGS, balance = balance)

    private fun credit(
        id: String,
        balance: Long,
    ) = Account(id = id, name = id, type = AccountType.CREDIT, balance = balance)

    private fun debt(
        id: String,
        balance: Long,
    ) = Account(id = id, name = id, type = AccountType.DEBT, balance = balance)

    private fun invest(
        id: String,
        market: Long,
        include: Boolean = true,
        balance: Long = 0,
    ) = Account(
        id = id,
        name = id,
        type = AccountType.INVEST,
        balance = balance,
        marketValueCents = market,
        costCents = market,
        includeInNetAssets = include,
    )

    @Test
    @DisplayName("现金与储蓄卡各自独立计入，便于报表拆开画饼图")
    fun cashAndSavingsSeparated() {
        val s = NetAssetsCalculator.compute(listOf(cash("c", 300_00), savings("s", 700_00)))
        assertEquals(300_00, s.cash)
        assertEquals(700_00, s.savings)
        assertEquals(1_000_00, s.assets)
        assertEquals(1_000_00, s.net)
    }

    @Test
    @DisplayName("投资账户按市值计，余额不重复计入资产")
    fun investUsesMarketValueOnly() {
        val s = NetAssetsCalculator.compute(listOf(invest("i", market = 1_200_00, balance = 1_000_00)))
        assertEquals(1_200_00, s.investMarket)
        assertEquals(1_200_00, s.assets, "余额与市值是同一笔钱的两种表示，不能相加")
    }

    @Test
    @DisplayName("不计入净资产的投资市值单列提示，不进总资产")
    fun investExcluded() {
        val s = NetAssetsCalculator.compute(listOf(invest("i", market = 500_00, include = false), savings("s", 100_00)))
        assertEquals(100_00, s.assets)
        assertEquals(500_00, s.investExcluded)
        assertEquals(100_00, s.net)
    }

    @Test
    @DisplayName("信用账户负余额计负债，多还的正余额按资产")
    fun creditBothDirections() {
        val owing = NetAssetsCalculator.compute(listOf(credit("c", -800_00), savings("s", 1_000_00)))
        assertEquals(800_00, owing.creditDebt)
        assertEquals(200_00, owing.net)

        val overpaid = NetAssetsCalculator.compute(listOf(credit("c", 50_00)))
        assertEquals(0L, overpaid.creditDebt)
        assertEquals(50_00, overpaid.net)
    }

    @Test
    @DisplayName("借贷账户：正值是待收（资产），负值是待还（负债）")
    fun debtBothDirections() {
        // v1.9.0 符号约定（PRD 2.1）：待收为正、待还为负。
        // 负数不再「翻正成资产」——借来的钱要还，它是负债。
        val borrow = NetAssetsCalculator.compute(listOf(debt("d", -600_00)))
        assertEquals(0L, borrow.lendOut)
        assertEquals(600_00, borrow.borrowIn)
        assertEquals(-600_00, borrow.net)

        val lend = NetAssetsCalculator.compute(listOf(debt("d", 400_00)))
        assertEquals(400_00, lend.lendOut)
        assertEquals(0L, lend.borrowIn)
        assertEquals(400_00, lend.net)
    }

    @Test
    @DisplayName("储蓄卡透支计入负债，不能被「资产类账户」掩盖")
    fun overdraftCountedAsLiability() {
        val s = NetAssetsCalculator.compute(listOf(savings("s", -200_00), cash("c", 100_00)))
        assertEquals(100_00, s.assets)
        assertEquals(200_00, s.overdraft)
        assertEquals(-100_00, s.net)
    }

    @Test
    @DisplayName("隐藏账户整体不计入，与列表可见性一致")
    fun hiddenExcluded() {
        val s = NetAssetsCalculator.compute(listOf(savings("s", 500_00), cash("hidden", 9_999_00, hidden = true)))
        assertEquals(500_00, s.net)
    }

    @Test
    @DisplayName("五类账户混合：借贷拆成借出款与借入款两边")
    fun mixedAccounts() {
        val s =
            NetAssetsCalculator.compute(
                listOf(
                    cash("c", 200_00),
                    savings("s", 1_000_00),
                    invest("i", market = 3_000_00),
                    credit("cr", -500_00),
                    debt("lend", 300_00),
                    debt("borrow", -100_00),
                ),
            )
        assertEquals(300_00, s.lendOut)
        assertEquals(100_00, s.borrowIn)
        assertEquals(4_500_00, s.assets)
        assertEquals(600_00, s.liabilities)
        assertEquals(3_900_00, s.net)
    }

    @Test
    @DisplayName("借贷四操作净资产不变：两侧账户余额互为相反数")
    fun debtOpsKeepNetStable() {
        // 数例来自 PRD 2.2：现金 10000、无借贷 → 借出 3000 → 收款 1000 → 借入 2000 → 还款 500。
        // 每步把「现金余额 + 借贷余额」一起喂进计算器，净资产必须恒为 10000。
        fun netOf(
            cashCents: Long,
            lendCents: Long,
            borrowCents: Long,
        ) = NetAssetsCalculator.compute(
            listOf(
                cash("c", cashCents),
                debt("lend", lendCents),
                debt("borrow", borrowCents),
            ),
        ).net

        assertEquals(10_000_00, netOf(10_000_00, 0, 0))
        assertEquals(10_000_00, netOf(7_000_00, 3_000_00, 0), "借出 3000：现金 −3000、待收 +3000")
        assertEquals(10_000_00, netOf(8_000_00, 2_000_00, 0), "收款 1000：现金 +1000、待收 −1000")
        assertEquals(10_000_00, netOf(10_000_00, 2_000_00, -2_000_00), "借入 2000：现金 +2000、待还 +2000")
        assertEquals(10_000_00, netOf(9_500_00, 2_000_00, -1_500_00), "还款 500：现金 −500、待还 −500")
    }

    @Test
    @DisplayName("坏账核销是唯一改变净资产的借贷操作：待收归零、资产实减")
    fun writeOffChangesNet() {
        // 核销后借贷账户余额 0、现金也不回补，净资产比核销前少 3000
        val before = NetAssetsCalculator.compute(listOf(cash("c", 7_000_00), debt("lend", 3_000_00))).net
        val after = NetAssetsCalculator.compute(listOf(cash("c", 7_000_00), debt("lend", 0))).net
        assertEquals(10_000_00, before)
        assertEquals(7_000_00, after)
    }

    @Test
    @DisplayName("空账户列表返回全零，不抛异常")
    fun empty() {
        val s = NetAssetsCalculator.compute(emptyList())
        assertEquals(0L, s.assets)
        assertEquals(0L, s.liabilities)
        assertEquals(0L, s.net)
    }
}
