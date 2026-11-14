package com.xxjz.ledger.domain.usecase

import com.xxjz.ledger.core.common.DateUtils
import com.xxjz.ledger.data.repository.AssetEventStore
import com.xxjz.ledger.domain.model.Account
import com.xxjz.ledger.domain.model.AssetEvent
import com.xxjz.ledger.domain.model.AssetEventKind
import com.xxjz.ledger.domain.model.Category
import com.xxjz.ledger.domain.model.Txn
import com.xxjz.ledger.domain.model.TxnType
import com.xxjz.ledger.domain.repository.AccountRepository
import com.xxjz.ledger.domain.repository.CategoryRepository
import com.xxjz.ledger.domain.repository.TxnRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject

/*
 * 资产报表四板块的查询入口（v1.8.0 / PRD 5.2）。
 *
 * 四个用例都只读、不写库；口径统一走 [BreakdownCalculator] 与 [NetWorthReplay]，
 * 保证「账户页的净资产」与「报表页的构成/趋势」永远是同一套算法。
 */

/** 资产构成（板块①）。 */
class GetAssetBreakdownUseCase
    @Inject
    constructor(
        private val accountRepository: AccountRepository,
    ) {
        fun observe(): Flow<AssetBreakdown> = accountRepository.observeWithBalances().map { BreakdownCalculator.assets(it) }

        operator fun invoke(accounts: List<Account>): AssetBreakdown = BreakdownCalculator.assets(accounts)

        suspend fun current(): AssetBreakdown = invoke(accountRepository.observeWithBalances().first())
    }

/** 负债构成（板块②）。 */
class GetLiabilityBreakdownUseCase
    @Inject
    constructor(
        private val accountRepository: AccountRepository,
    ) {
        fun observe(): Flow<LiabilityBreakdown> = accountRepository.observeWithBalances().map { BreakdownCalculator.liabilities(it) }

        operator fun invoke(accounts: List<Account>): LiabilityBreakdown = BreakdownCalculator.liabilities(accounts)

        suspend fun current(): LiabilityBreakdown = invoke(accountRepository.observeWithBalances().first())
    }

/**
 * 净资产趋势（板块③）。
 *
 * 不从快照表回算：直接取流水 + 元事件重放（PRD 6.2 明确「不存快照」）。
 * - 月视图：近 12 个月（含本月），每月最后一天（本月取今天）的净资产；
 * - 年视图：近 5 年（含今年），每年最后一天（今年取今天）的净资产。
 */
class GetNetWorthTrendUseCase
    @Inject
    constructor(
        private val accountRepository: AccountRepository,
        private val txnRepository: TxnRepository,
        private val assetEventStore: AssetEventStore,
    ) {
        companion object {
            /** 月视图点数。 */
            const val MONTH_SPAN = 12

            /** 年视图点数。 */
            const val YEAR_SPAN = 5
        }

        fun observe(
            period: TrendPeriod,
            today: LocalDate = DateUtils.today(),
        ): Flow<NetWorthTrend> =
            combine(
                accountRepository.observeAll(),
                txnRepository.observeAllForReport(),
                assetEventStore.observeAll(),
            ) { accounts, txns, events ->
                build(period, today, accounts, txns, events)
            }

        /** 一次性计算（测试与其他用例复用）。 */
        suspend fun current(
            period: TrendPeriod,
            today: LocalDate = DateUtils.today(),
        ): NetWorthTrend =
            build(
                period = period,
                today = today,
                accounts = accountRepository.observeAll().first(),
                txns = txnRepository.allForReport(),
                events = assetEventStore.listAll(),
            )

        private fun build(
            period: TrendPeriod,
            today: LocalDate,
            accounts: List<Account>,
            txns: List<Txn>,
            events: List<AssetEvent>,
        ): NetWorthTrend {
            val replayed = NetWorthReplay.replay(NetWorthReplay.initialNet(accounts), txns, events)
            return NetWorthTrend(period, pointsOf(period, today, replayed, NetWorthReplay.initialNet(accounts)))
        }

        private fun pointsOf(
            period: TrendPeriod,
            today: LocalDate,
            replayed: List<Pair<Long, Long>>,
            base: Long,
        ): List<TrendPoint> {
            val months =
                when (period) {
                    TrendPeriod.MONTH ->
                        (0 until MONTH_SPAN).map {
                            YearMonth.of(today.year, today.monthValue).minusMonths((MONTH_SPAN - 1 - it).toLong())
                        }
                    TrendPeriod.YEAR -> (0 until YEAR_SPAN).map { YearMonth.of(today.year - (YEAR_SPAN - 1 - it), 1) }
                }
            // 当前这一格只算到「今天」，否则月视图本月、年视图今年会出现未来数据
            val todayEnd = DateUtils.startOfDayMillis(today) + DAY_MILLIS - 1
            return months.mapIndexed { index, month ->
                val isCurrent = index == months.lastIndex
                val endMillis =
                    when {
                        isCurrent -> todayEnd
                        period == TrendPeriod.MONTH -> DateUtils.monthRange(month.year, month.monthValue).last
                        else -> DateUtils.yearRange(month.year).last
                    }
                val label = if (period == TrendPeriod.MONTH) "${month.monthValue}月" else "${month.year}"
                TrendPoint(
                    label = label,
                    at = endMillis,
                    net = replayed.lastOrNull { it.first <= endMillis }?.second ?: base,
                )
            }
        }
    }

/**
 * 资产变化记录（板块④）。
 *
 * 由两路数据合并（PRD 6.4）：
 * 1. 流水回算：收入 / 支出 / 退款 / 报销 / 删除流水；
 * 2. 元事件：改市值 / 改初始余额。
 *
 * **转账（含投资买卖）不记录**——一减一增，净资产没变。
 */
class GetAssetChangesUseCase
    @Inject
    constructor(
        private val accountRepository: AccountRepository,
        private val txnRepository: TxnRepository,
        private val categoryRepository: CategoryRepository,
        private val assetEventStore: AssetEventStore,
    ) {
        companion object {
            /** 默认返回条数：够翻一屏半，再往上翻收益太低。 */
            const val DEFAULT_LIMIT = 200
        }

        fun observe(limit: Int = DEFAULT_LIMIT): Flow<List<AssetChange>> =
            combine(
                accountRepository.observeAll(),
                txnRepository.observeAllForReport(),
                categoryRepository.observeAll(),
                assetEventStore.observeAll(),
            ) { accounts, txns, categories, events ->
                build(accounts, txns, categories.associateBy { it.id }, events, limit)
            }

        /** 一次性计算（测试复用）。 */
        suspend fun current(limit: Int = DEFAULT_LIMIT): List<AssetChange> =
            build(
                accounts = accountRepository.observeAll().first(),
                txns = txnRepository.allForReport(),
                categories = categoryRepository.observeAll().first().associateBy { it.id },
                events = assetEventStore.listAll(),
                limit = limit,
            )

        private fun build(
            accounts: List<Account>,
            txns: List<Txn>,
            categories: Map<String, Category>,
            events: List<AssetEvent>,
            limit: Int,
        ): List<AssetChange> {
            // 完整回放一次：`netAfter` 必须建立在「含前缀」的累计值上，否则中段条目的净资产会错位
            val replayed = NetWorthReplay.replay(NetWorthReplay.initialNet(accounts), txns, events)

            fun netAt(at: Long): Long = replayed.lastOrNull { it.first <= at }?.second ?: NetWorthReplay.initialNet(accounts)

            val rows = ArrayList<AssetChange>(txns.size + events.size)
            // 转账与借贷对净资产净额为 0，天然被 deltaOf 过滤掉（PRD 6.4「转账不记录」）
            txns.filter { NetWorthReplay.deltaOf(it) != 0L }.forEach { rows += txnChange(it, categories, ::netAt) }
            events.forEach { rows += eventChange(it, ::netAt) }

            return rows
                .sortedWith(compareByDescending<AssetChange> { it.at }.thenByDescending { it.key })
                .take(limit)
        }

        private fun txnChange(
            txn: Txn,
            categories: Map<String, Category>,
            netAt: (Long) -> Long,
        ): AssetChange {
            val reason = txn.note.ifBlank { txn.merchantName.orEmpty() }
            val name = categoryNameOf(categories, txn.categoryId)
            val suffix = if (reason.isEmpty()) "" else "：$reason"
            val deleted = txn.deletedAt != null
            val (kind, title) =
                when {
                    deleted -> AssetChangeKind.DELETED to "删除${typeWord(txn.type)}：${reason.ifBlank { name }}$suffix"
                    txn.refundOfTxnId != null -> AssetChangeKind.REFUND to "退款：${reason.ifBlank { name }}"
                    txn.parentTxnId != null -> AssetChangeKind.REIMBURSE to "报销到账：${reason.ifBlank { name }}"
                    txn.type == TxnType.INCOME -> AssetChangeKind.INCOME to "$name 收入$suffix"
                    else -> AssetChangeKind.EXPENSE to "$name 支出$suffix"
                }
            // 被删掉的那笔，其影响在「删除时刻」反向撤销，所以符号取反
            val delta = if (deleted) -NetWorthReplay.deltaOf(txn) else NetWorthReplay.deltaOf(txn)
            val at = txn.deletedAt ?: txn.dateTime
            return AssetChange(
                txnId = txn.id,
                key = txn.id,
                at = at,
                title = title,
                delta = delta,
                netAfter = netAt(at),
                kind = kind,
            )
        }

        private fun eventChange(
            event: AssetEvent,
            netAt: (Long) -> Long,
        ): AssetChange {
            val (kind, title) =
                when (event.kind) {
                    AssetEventKind.MARKET_VALUE -> AssetChangeKind.MARKET_VALUE to "调整市值：${event.accountName}"
                    AssetEventKind.INITIAL_BALANCE -> AssetChangeKind.INITIAL_BALANCE to "改初始余额：${event.accountName}"
                }
            return AssetChange(
                txnId = null,
                key = event.id,
                at = event.at,
                title = title,
                delta = event.deltaCents,
                netAfter = netAt(event.at),
                kind = kind,
                amountAfter = event.amountAfterCents,
            )
        }

        private fun typeWord(type: TxnType): String =
            when (type) {
                TxnType.EXPENSE -> "支出"
                TxnType.INCOME -> "收入"
                else -> "流水"
            }
    }

private const val DAY_MILLIS = 86_400_000L
