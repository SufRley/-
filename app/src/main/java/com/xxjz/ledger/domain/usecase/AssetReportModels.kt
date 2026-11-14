package com.xxjz.ledger.domain.usecase

import com.xxjz.ledger.core.common.DateUtils
import com.xxjz.ledger.domain.model.Account
import com.xxjz.ledger.domain.model.AccountType
import com.xxjz.ledger.domain.model.AssetEvent
import com.xxjz.ledger.domain.model.AssetEventKind
import com.xxjz.ledger.domain.model.Category
import com.xxjz.ledger.domain.model.Txn
import com.xxjz.ledger.domain.model.TxnType
import java.time.YearMonth
import kotlin.math.abs

/*
 * 资产报表四板块的数据模型与纯计算（v1.8.0 / PRD 5.2、6.4）。
 *
 * 为什么把计算全部放在纯函数里：资产报表是「只读投影」，一旦算错，
 * 用户看到的历史净资产曲线会长期错下去；把回放逻辑抽成无副作用的纯函数，
 * 就能用构造数据穷举「转账不计、退款相抵、删流水回滚」这些容易出错的边界。
 */

/** 资产构成的一段（饼图一瓣）。 */
data class AssetSlice(
    val label: String,
    val amount: Long,
) {
    /** 占该板块合计的百分比（0~100），合计为 0 时返回 0，避免除零。 */
    fun percent(total: Long): Int = if (total <= 0) 0 else ((amount.toDouble() / total) * 100).toInt()
}

/** 资产构成：现金 / 储蓄卡 / 计入净资产的投资（按市值）。 */
data class AssetBreakdown(
    val slices: List<AssetSlice>,
    /** 未计入净资产的投资市值，只做提示，不参与饼图与合计。 */
    val excludedInvest: Long = 0,
) {
    val total: Long get() = slices.sumOf { it.amount }

    /** 只要金额 > 0 的段：0 元段画出来是一瓣不存在的弧，没意义。 */
    val nonZero: List<AssetSlice> get() = slices.filter { it.amount > 0 }

    val isEmpty: Boolean get() = nonZero.isEmpty()
}

/** 负债构成：信用欠款 / 借贷负债 / 储蓄卡透支。 */
data class LiabilityBreakdown(
    val slices: List<AssetSlice>,
) {
    val total: Long get() = slices.sumOf { it.amount }

    val nonZero: List<AssetSlice> get() = slices.filter { it.amount > 0 }

    val isEmpty: Boolean get() = nonZero.isEmpty()
}

/** 趋势粒度。 */
enum class TrendPeriod {
    /** 近 12 个月，逐月一个点。 */
    MONTH,

    /** 逐年，一年一个点。 */
    YEAR,
}

/** 趋势上的一个点：某个时点（月末 / 年末）的净资产。 */
data class TrendPoint(
    val label: String,
    val at: Long,
    val net: Long,
)

/** 净资产趋势。 */
data class NetWorthTrend(
    val period: TrendPeriod,
    val points: List<TrendPoint>,
) {
    val latest: Long? get() = points.lastOrNull()?.net

    /** 与上一个点相比的变化额，点数不足返回 null。 */
    val change: Long?
        get() = if (points.size >= 2) points.last().net - points[points.size - 2].net else null

    /**
     * 折线图的归一化比例（0f~1f）。
     *
     * 净资产可能为负，所以不能直接用「值 / 最大值」；以最小值~最大值区间映射，
     * 全场相等时全部落在 0.5（画出中间一条平线，而不是贴底）。
     */
    fun ratios(): List<Float> {
        if (points.isEmpty()) return emptyList()
        val values = points.map { it.net }
        val min = values.min()
        val max = values.max()
        if (max == min) return values.map { 0.5f }
        return values.map { ((it - min).toDouble() / (max - min)).toFloat() }
    }
}

/** 资产变化记录的一条。 */
data class AssetChange(
    /** 关联流水 id；元数据事件（改市值 / 改初始余额）为 null。 */
    val txnId: String?,
    /** 排序备用键（流水 id 或事件 id），同一毫秒多条时保证顺序稳定。 */
    val key: String,
    val at: Long,
    /** 事件描述，如「餐饮支出」「调整市值：某某基金」。 */
    val title: String,
    /** 金额（分），正数表示净资产增加，负数表示减少。 */
    val delta: Long,
    /** 变动后净资产（分）。 */
    val netAfter: Long,
    val kind: AssetChangeKind,
    /** 事件后的目标值（改市值 = 新市值），仅元事件有；展示「调整到 xx」用。 */
    val amountAfter: Long? = null,
)

/** 变化记录的分类，决定列表图标与文案（不参与排序）。 */
enum class AssetChangeKind {
    INCOME,
    EXPENSE,
    REFUND,
    REIMBURSE,
    DELETED,
    MARKET_VALUE,
    INITIAL_BALANCE,
}

/**
 * 净资产回放引擎。
 *
 * 净资产 = 账户余额之和 − 借贷净负债，而账户余额本身是「初始余额 + 流水聚合」，
 * 因此只要从**所有账户的初始余额之和**出发，按时间顺序重放每笔流水，就能得到任意时点净资产：
 *
 * | 流水类型 | 对净资产的影响 |
 * |---|---|
 * | 支出 | −金额（钱出去了，账上少了） |
 * | 收入 / 退款 | +金额 |
 * | 转账（含投资买卖） | 一侧减一侧增，**净额 0**，完全不参与计算 |
 * | 借贷 | 借入 +、借出 −，已计入账户余额，同样净额 0 |
 * | 软删除 | 把该笔原本的影响**反向**撤掉 |
 *
 * 这正是「不从快照回算」的原因：只要流水完整，历史曲线就永远和明细一致（PRD 6.2）。
 */
object NetWorthReplay {
    /** 起始净资产：全部账户的初始余额之和（隐藏账户也算，它是真实资产的一部分）。 */
    fun initialNet(accounts: List<Account>): Long = accounts.sumOf { it.initialBalance }

    /**
     * 单笔流水对净资产的影响（分）。
     *
     * 返回 0 表示这笔流水不改变净资产（转账 / 借贷 / 被撤销）。
     */
    fun deltaOf(txn: Txn): Long =
        when (txn.type) {
            TxnType.EXPENSE -> -txn.amount
            TxnType.INCOME -> txn.amount
            TxnType.TRANSFER, TxnType.DEBT -> 0L
        }

    /**
     * 按时间顺序回放，产出「时刻 → 净资产」。
     *
     * @param base 起始净资产
     * @param txns 全部流水（可含已软删，软删的会自动反向撤销）
     * @param extraEvents 不改流水、只改余额/市值的事件（改市值 / 改初始余额）
     * @return 按时间升序的 (时刻, 该笔之后的净资产) 序列，用于「变动后净资产」与月末取值
     */
    fun replay(
        base: Long,
        txns: List<Txn>,
        extraEvents: List<AssetEvent> = emptyList(),
    ): List<Pair<Long, Long>> {
        // 删除也是「发生在删除那一刻」的一次变化，和原流水分开排，否则同一天里顺序会乱
        data class Step(
            val at: Long,
            val delta: Long,
            /** 同一时刻的稳定排序：原流水 0、删除 1、元事件 2，保证重放结果可复现。 */
            val order: Int,
            val id: String,
        )

        val steps = ArrayList<Step>(txns.size * 2 + extraEvents.size)
        txns.forEach { txn ->
            val delta = deltaOf(txn)
            if (delta != 0L) {
                steps += Step(txn.dateTime, delta, 0, txn.id)
                // 软删后这笔不再影响余额：在删除时刻反向撤销
                txn.deletedAt?.let { steps += Step(it, -delta, 1, txn.id) }
            }
        }
        extraEvents.forEach { event ->
            // 两种事件都只改余额/市值、不动流水，因此对净资产的影响就是记录里的变化量本身
            // （改市值的 deltaCents = 新市值 − 旧市值，见 AccountRepositoryImpl.updateMarketValue）
            val delta =
                when (event.kind) {
                    AssetEventKind.MARKET_VALUE, AssetEventKind.INITIAL_BALANCE -> event.deltaCents
                }
            if (delta != 0L) steps += Step(event.at, delta, 2, event.id)
        }

        var net = base
        return steps
            .sortedWith(compareBy({ it.at }, { it.order }, { it.id }))
            .map { step ->
                net += step.delta
                step.at to net
            }
    }

    /**
     * 取月末净资产曲线。
     *
     * @param start 起点（含），通常是 11 个月前的月初
     * @param end 终点（含），通常是今天
     * @param monthEnds 需要取值的月份列表（按时间升序）
     */
    fun monthEndValues(
        points: List<Pair<Long, Long>>,
        monthEnds: List<YearMonth>,
    ): List<Long> =
        monthEnds.map { ym ->
            val endMillis = DateUtils.monthRange(ym.year, ym.monthValue).last
            // 该月末之前（含）最后一次变化之后的净资产；没有变化则为 0（由调用方补 base）
            points.lastOrNull { it.first <= endMillis }?.second ?: 0L
        }

    /** 判断两个毫秒时刻是否落在同一毫秒（回放排序的稳定性测试用）。 */
    fun sameMoment(
        a: Long,
        b: Long,
    ): Boolean = abs(a - b) < 1
}

/**
 * 由账户列表直接算资产 / 负债构成（与 [NetAssetsCalculator] 同一口径，避免两处漂移）。
 */
object BreakdownCalculator {
    fun assets(accounts: List<Account>): AssetBreakdown {
        val visible = accounts.filter { !it.hidden }
        var cash = 0L
        var savings = 0L
        var invest = 0L
        var excluded = 0L
        visible.forEach { account ->
            when (account.type) {
                AccountType.CASH -> if (account.balance > 0) cash += account.balance else savings += 0
                AccountType.SAVINGS -> if (account.balance > 0) savings += account.balance
                AccountType.CREDIT -> if (account.balance > 0) savings += account.balance
                AccountType.DEBT -> if (account.balance < 0) savings += -account.balance
                AccountType.INVEST ->
                    if (account.includeInNetAssets) invest += account.marketValueCents else excluded += account.marketValueCents
            }
        }
        // 现金账户的正余额单列；储蓄卡桶里可能混入了信用卡多还、借贷被欠、透支抵减，统一叫「储蓄卡」
        return AssetBreakdown(
            slices =
                listOf(
                    AssetSlice("现金", cash),
                    AssetSlice("储蓄卡", savings),
                    AssetSlice("投资", invest),
                ),
            excludedInvest = excluded,
        )
    }

    fun liabilities(accounts: List<Account>): LiabilityBreakdown {
        val visible = accounts.filter { !it.hidden }
        var credit = 0L
        var debt = 0L
        var overdraft = 0L
        visible.forEach { account ->
            when (account.type) {
                AccountType.CREDIT -> if (account.balance < 0) credit += -account.balance
                AccountType.DEBT -> if (account.balance > 0) debt += account.balance
                AccountType.CASH, AccountType.SAVINGS -> if (account.balance < 0) overdraft += -account.balance
                AccountType.INVEST -> Unit
            }
        }
        return LiabilityBreakdown(
            slices =
                listOf(
                    AssetSlice("信用欠款", credit),
                    AssetSlice("借贷负债", debt),
                    AssetSlice("储蓄卡透支", overdraft),
                ),
        )
    }
}

/** 变化记录描述用的分类名查找（分类可能已被删除，兜底成「未分类」）。 */
internal fun categoryNameOf(
    categories: Map<String, Category>,
    id: String?,
): String = id?.let { categories[it]?.name } ?: "未分类"
