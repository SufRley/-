package com.xxjz.ledger.domain.usecase

import com.xxjz.ledger.domain.model.Account
import com.xxjz.ledger.domain.model.AccountType
import com.xxjz.ledger.domain.model.AssetEvent
import com.xxjz.ledger.domain.model.AssetEventKind
import com.xxjz.ledger.domain.model.Txn
import com.xxjz.ledger.domain.model.TxnType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 资产 / 负债构成（PRD 5.2 板块①②、6.3 口径）。
 *
 * 重点覆盖三件容易错的事：
 * 1. 投资账户**按市值计、不叠加余额**（否则净资产翻倍）；
 * 2. 不计入净资产的投资既不算资产、也不算「未分类」里的资产，只单独提示；
 * 3. 透支要进负债，而不是把资产减成负数。
 */
class AssetBreakdownTest {
    private fun account(
        id: String,
        type: AccountType,
        balance: Long = 0,
        market: Long = 0,
        include: Boolean = true,
        hidden: Boolean = false,
    ) = Account(
        id = id,
        name = id,
        type = type,
        balance = balance,
        marketValueCents = market,
        includeInNetAssets = include,
        hidden = hidden,
    )

    @Test
    fun assetsSplitIntoThreeSlices() {
        val breakdown =
            BreakdownCalculator.assets(
                listOf(
                    account("cash", AccountType.CASH, balance = 1_000_00),
                    account("sav", AccountType.SAVINGS, balance = 2_000_00),
                    account("inv", AccountType.INVEST, balance = 9_999_00, market = 3_000_00),
                ),
            )
        assertEquals(listOf(1_000_00L, 2_000_00L, 3_000_00L), breakdown.slices.map { it.amount })
        assertEquals(6_000_00L, breakdown.total, "投资按市值计，不叠加余额")
    }

    @Test
    fun excludedInvestNotInTotalButHinted() {
        val breakdown =
            BreakdownCalculator.assets(
                listOf(
                    account("cash", AccountType.CASH, balance = 1_000_00),
                    account("inv", AccountType.INVEST, market = 5_000_00, include = false),
                ),
            )
        assertEquals(1_000_00L, breakdown.total)
        assertEquals(5_000_00L, breakdown.excludedInvest)
    }

    @Test
    fun hiddenAccountsIgnored() {
        val breakdown =
            BreakdownCalculator.assets(
                listOf(
                    account("cash", AccountType.CASH, balance = 100_00),
                    account("off", AccountType.CASH, balance = 999_00, hidden = true),
                ),
            )
        assertEquals(100_00L, breakdown.total, "隐藏账户不计入，与列表可见性一致")
    }

    @Test
    fun creditSurplusCountsAsSaving() {
        // 信用卡多还导致正余额：按资产算更符合直觉，归到储蓄卡桶
        val breakdown =
            BreakdownCalculator.assets(
                listOf(account("credit", AccountType.CREDIT, balance = 300_00)),
            )
        assertEquals(300_00L, breakdown.slices.first { it.label == "储蓄卡" }.amount)
    }

    @Test
    fun liabilitiesSplitCreditAndDebt() {
        val liability =
            BreakdownCalculator.liabilities(
                listOf(
                    account("credit", AccountType.CREDIT, balance = -2_000_00),
                    account("debt", AccountType.DEBT, balance = 500_00),
                    account("lend", AccountType.DEBT, balance = -800_00),
                ),
            )
        assertEquals(2_000_00L, liability.slices.first { it.label == "信用欠款" }.amount)
        assertEquals(500_00L, liability.slices.first { it.label == "借贷负债" }.amount)
        assertEquals(0L, liability.slices.first { it.label == "储蓄卡透支" }.amount)
        assertEquals(2_500_00L, liability.total, "被借出去的钱是资产，不是负债")
    }

    @Test
    fun overdraftGoesToLiabilityNotNegativeAsset() {
        val accounts = listOf(account("cash", AccountType.CASH, balance = -200_00))
        val assets = BreakdownCalculator.assets(accounts)
        val liability = BreakdownCalculator.liabilities(accounts)
        assertEquals(0L, assets.total, "透支不能把资产算成负数")
        assertEquals(200_00L, liability.total)
    }

    @Test
    fun zeroSlicesAreDroppedForChart() {
        val breakdown =
            BreakdownCalculator.assets(listOf(account("cash", AccountType.CASH, balance = 100_00)))
        assertEquals(1, breakdown.nonZero.size, "0 元段不画饼图")
        assertEquals(listOf("现金"), breakdown.nonZero.map { it.label })
    }

    @Test
    fun emptyAccountsGiveEmptyBreakdown() {
        val breakdown = BreakdownCalculator.assets(emptyList())
        assertTrue(breakdown.isEmpty)
        assertEquals(0L, breakdown.total)
    }

    @Test
    fun percentGuardsDivideByZero() {
        val slice = AssetSlice("现金", 0)
        assertEquals(0, slice.percent(0))
        assertEquals(50, AssetSlice("现金", 50).percent(100))
    }

    @Test
    fun breakdownMatchesNetAssetsCalculator() {
        // 报表页与账户页必须是同一套数字，否则用户会以为哪里算错了
        val accounts =
            listOf(
                account("cash", AccountType.CASH, balance = 1_234_56),
                account("sav", AccountType.SAVINGS, balance = 5_678_90),
                account("credit", AccountType.CREDIT, balance = -1_000_00),
                account("debt", AccountType.DEBT, balance = 300_00),
                account("inv", AccountType.INVEST, balance = 777_00, market = 2_345_67),
            )
        val summary = NetAssetsCalculator.compute(accounts)
        val assets = BreakdownCalculator.assets(accounts)
        val liability = BreakdownCalculator.liabilities(accounts)
        assertEquals(summary.assets, assets.total)
        assertEquals(summary.liabilities, liability.total)
        assertFalse(assets.isEmpty)
    }
}

/**
 * 净资产回放（PRD 6.2 / 6.4）。
 *
 * 转账与借贷净额为 0、软删除要反向撤销、同毫秒事件顺序稳定——这三条是趋势曲线可信的前提。
 */
class NetWorthReplayTest {
    private val t0 = 1_700_000_000_000L

    private fun txn(
        id: String,
        type: TxnType,
        amount: Long,
        at: Long = t0,
        deletedAt: Long? = null,
        refundOf: String? = null,
    ) = Txn(
        id = id,
        type = type,
        amount = amount,
        categoryId = null,
        accountId = "a1",
        dateTime = at,
        dateKey = 20261114,
        deletedAt = deletedAt,
        refundOfTxnId = refundOf,
        createdAt = at,
        updatedAt = at,
    )

    @Test
    fun expenseDecreasesIncomeIncreases() {
        val points =
            NetWorthReplay.replay(
                base = 1_000_00,
                txns =
                    listOf(
                        txn("t1", TxnType.EXPENSE, 100_00),
                        txn("t2", TxnType.INCOME, 300_00, at = t0 + 1),
                    ),
            )
        assertEquals(listOf(900_00L, 1_200_00L), points.map { it.second })
    }

    @Test
    fun transferIsNeutral() {
        val points = NetWorthReplay.replay(1_000_00, listOf(txn("t1", TxnType.TRANSFER, 500_00)))
        assertTrue(points.isEmpty(), "转账一减一增，净资产不变，回放里不应产生任何步骤")
    }

    @Test
    fun debtIsNeutral() {
        val points = NetWorthReplay.replay(1_000_00, listOf(txn("t1", TxnType.DEBT, 500_00)))
        assertTrue(points.isEmpty(), "借贷已计入账户余额，回放不再重复计算")
    }

    @Test
    fun softDeleteRollsBack() {
        val deleteAt = t0 + 10_000
        val points =
            NetWorthReplay.replay(
                base = 1_000_00,
                txns = listOf(txn("t1", TxnType.EXPENSE, 100_00, deletedAt = deleteAt)),
            )
        assertEquals(2, points.size)
        assertEquals(900_00L, points[0].second, "先按支出扣掉")
        assertEquals(1_000_00L, points[1].second, "删除时刻再撤销回原值")
    }

    @Test
    fun metaEventAppliesDelta() {
        val event =
            AssetEvent(
                id = "e1",
                at = t0 + 5,
                kind = AssetEventKind.MARKET_VALUE,
                accountId = "inv",
                accountName = "基金",
                deltaCents = 200_00,
                amountAfterCents = 1_200_00,
            )
        val points = NetWorthReplay.replay(1_000_00, emptyList(), listOf(event))
        assertEquals(listOf(1_200_00L), points.map { it.second })
    }

    @Test
    fun zeroDeltaEventsSkipped() {
        val event =
            AssetEvent(
                id = "e1",
                at = t0,
                kind = AssetEventKind.MARKET_VALUE,
                accountId = "inv",
                accountName = "基金",
                deltaCents = 0,
            )
        assertTrue(NetWorthReplay.replay(1_000_00, emptyList(), listOf(event)).isEmpty())
    }

    @Test
    fun sameMomentOrderIsStable() {
        // 同一毫秒里「原流水」必须先于「删除」处理，否则回放结果会随输入顺序摆动
        val at = t0
        val points =
            NetWorthReplay.replay(
                base = 0,
                txns = listOf(txn("t1", TxnType.EXPENSE, 100_00, at = at, deletedAt = at)),
            )
        assertEquals(2, points.size)
        assertEquals(-100_00L, points[0].second)
        assertEquals(0L, points[1].second)
    }

    @Test
    fun initialNetSumsInitialBalances() {
        val accounts =
            listOf(
                Account(id = "a", name = "a", type = AccountType.CASH, initialBalance = 100_00),
                Account(id = "b", name = "b", type = AccountType.SAVINGS, initialBalance = 250_00),
            )
        assertEquals(350_00L, NetWorthReplay.initialNet(accounts))
    }

    @Test
    fun ratioHandlesNegativeNetWorth() {
        val trend =
            NetWorthTrend(
                period = TrendPeriod.MONTH,
                points =
                    listOf(
                        TrendPoint("1月", 1, -1_000_00),
                        TrendPoint("2月", 2, 0),
                        TrendPoint("3月", 3, 1_000_00),
                    ),
            )
        assertEquals(listOf(0f, 0.5f, 1f), trend.ratios(), "净资产可为负，归一化按 min~max 区间映射")
    }

    @Test
    fun ratioFlatWhenAllEqual() {
        val trend =
            NetWorthTrend(
                period = TrendPeriod.MONTH,
                points = listOf(TrendPoint("1月", 1, 500_00), TrendPoint("2月", 2, 500_00)),
            )
        assertEquals(listOf(0.5f, 0.5f), trend.ratios(), "全等时画中线，不贴底也不贴顶")
    }

    @Test
    fun changeComparesLastTwoPoints() {
        val trend =
            NetWorthTrend(
                period = TrendPeriod.MONTH,
                points = listOf(TrendPoint("1月", 1, 100_00), TrendPoint("2月", 2, 350_00)),
            )
        assertEquals(250_00L, trend.change)
        assertEquals(350_00L, trend.latest)
    }
}
