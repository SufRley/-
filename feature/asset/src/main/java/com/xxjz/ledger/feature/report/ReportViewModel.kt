package com.xxjz.ledger.feature.report

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xxjz.ledger.core.common.DateUtils
import com.xxjz.ledger.domain.model.Category
import com.xxjz.ledger.domain.model.Txn
import com.xxjz.ledger.domain.model.TxnType
import com.xxjz.ledger.domain.repository.CategoryRepository
import com.xxjz.ledger.domain.repository.TxnRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject

/** 报表视图：饼图 / 排行 / 趋势（P07 三视图切换）。 */
enum class ReportView(val label: String) {
    PIE("饼图"),
    RANK("排行"),
    TREND("趋势"),
}

/** 年度总结页 P09 状态。 */
data class YearSummary(
    val year: Int = DateUtils.today().year,
    val months: List<TrendPoint> = emptyList(),
    val topCategories: List<RankItem> = emptyList(),
    val topMerchants: List<RankItem> = emptyList(),
    val expense: Long = 0,
    val income: Long = 0,
    val lastYearExpense: Long = 0,
    val lastYearIncome: Long = 0,
) {
    val balance: Long get() = income - expense
    val expenseChange: Int? get() = ReportMath.changePercent(expense, lastYearExpense)
    val hasData: Boolean get() = expense > 0 || income > 0
}

/** P07 报表页状态。 */
data class ReportUiState(
    val window: ReportWindow = ReportWindow(DateUtils.today(), ReportPeriod.MONTH),
    val type: TxnType = TxnType.EXPENSE,
    val view: ReportView = ReportView.PIE,
    val summary: ReportSummary = ReportSummary(),
    val loading: Boolean = true,
) {
    val label: String get() = window.label
}

/**
 * 报表聚合（AC-35 ~ AC-40 / AC-43）。
 *
 * 口径统一：所有金额都来自 `observeInRange` 过滤后的列表，
 * 且此处再显式剔除 `excludedFromStats` 与非收支类型，保证与首页/日历完全一致（AC-43）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ReportViewModel
    @Inject
    constructor(
        private val txnRepository: TxnRepository,
        private val categoryRepository: CategoryRepository,
        private val yearSummaryUseCase: YearSummaryUseCase,
    ) : ViewModel() {
        private val window = MutableStateFlow(ReportWindow(DateUtils.today(), ReportPeriod.MONTH))
        private val type = MutableStateFlow(TxnType.EXPENSE)
        private val view = MutableStateFlow(ReportView.PIE)

        val state: StateFlow<ReportUiState> =
            combine(window, type, categoryRepository.observeAll()) { w, t, cats -> Triple(w, t, cats) }
                .flatMapLatest { (w, t, cats) ->
                    val range = w.range
                    val prev = previousRange(w)
                    combine(
                        txnRepository.observeInRange(range.first, range.last),
                        txnRepository.observeInRange(prev.first, prev.last),
                        combine(type, view) { ty, v -> ty to v },
                    ) { current, previous, (ty, v) ->
                        build(w, ty, v, current, previous, cats)
                    }
                }.stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(5_000),
                    initialValue = ReportUiState(),
                )

        /**
         * 对比区间：
         * - 本月（含当前月）：本月至今 vs 上月同期（同一天，短月取月末），月中看才不会永远是跌；
         * - 历史月：整月 vs 上月整月；
         * - 周/年：上一个同粒度窗口。
         */
        private fun previousRange(w: ReportWindow): LongRange {
            val today = DateUtils.today()
            val isCurrentMonth = w.anchor.year == today.year && w.anchor.monthValue == today.monthValue
            return when {
                w.period == ReportPeriod.MONTH && isCurrentMonth -> {
                    val prevMonth = YearMonth.from(w.anchor).minusMonths(1)
                    val start = DateUtils.monthRange(prevMonth.year, prevMonth.monthValue).first
                    start..ReportMath.sameSpanEndOfLastMonth(w.anchor, today.dayOfMonth)
                }
                w.period == ReportPeriod.MONTH -> {
                    val prevMonth = YearMonth.from(w.anchor).minusMonths(1)
                    DateUtils.monthRange(prevMonth.year, prevMonth.monthValue)
                }
                else -> w.shift(-1).range
            }
        }

        private fun build(
            w: ReportWindow,
            ty: TxnType,
            viewMode: ReportView,
            current: List<Txn>,
            previous: List<Txn>,
            categories: List<Category>,
        ): ReportUiState {
            val counted = current.filter { !it.isDeleted && !it.excludedFromStats }
            val prevCounted = previous.filter { !it.isDeleted && !it.excludedFromStats }
            val ofType = counted.filter { it.type == ty }
            val prevOfType = prevCounted.filter { it.type == ty }

            val expense = counted.filter { it.type == TxnType.EXPENSE }.sumOf { it.amount }
            val income = counted.filter { it.type == TxnType.INCOME }.sumOf { it.amount }

            val parentOf = categories.associate { it.id to (it.parentId ?: it.id) }
            val nameOf = categories.associate { it.id to it.name }

            fun rollup(list: List<Txn>): Map<String, Long> =
                list.groupingBy { txn -> txn.categoryId?.let { parentOf[it] ?: it } ?: "__none__" }
                    .fold(0L) { acc, txn -> acc + txn.amount }

            val currentByRoot = rollup(ofType)
            val prevByRoot = rollup(prevOfType)
            val countByRoot = ofType.groupingBy { it.categoryId?.let { id -> parentOf[id] ?: id } ?: "__none__" }.eachCount()

            val ranks =
                ReportMath.buildRanks(
                    rows =
                        currentByRoot.map { (id, amount) ->
                            Triple(id, if (id == "__none__") "未分类" else nameOf[id] ?: "未分类", amount)
                        },
                    previous = prevByRoot,
                    countOf = countByRoot,
                )

            val days = windowDays(w)
            val trend =
                when (w.period) {
                    ReportPeriod.YEAR -> {
                        val exp =
                            counted.filter { it.type == TxnType.EXPENSE }
                                .groupingBy { DateUtils.toDate(it.dateTime).monthValue }
                                .fold(0L) { a, t -> a + t.amount }
                        val inc =
                            counted.filter { it.type == TxnType.INCOME }
                                .groupingBy { DateUtils.toDate(it.dateTime).monthValue }
                                .fold(0L) { a, t -> a + t.amount }
                        ReportMath.yearMonths(exp, inc)
                    }
                    else -> {
                        val indexOf = days.withIndex().associate { (i, d) -> DateUtils.dateKeyOf(d) to i }
                        val exp =
                            counted.filter { it.type == TxnType.EXPENSE }
                                .groupingBy { indexOf[it.dateKey] ?: -1 }
                                .fold(0L) { a, t -> a + t.amount }
                        val inc =
                            counted.filter { it.type == TxnType.INCOME }
                                .groupingBy { indexOf[it.dateKey] ?: -1 }
                                .fold(0L) { a, t -> a + t.amount }
                        ReportMath.buildTrend(
                            labels = days.map { d -> "${d.monthValue}/${d.dayOfMonth}" },
                            expenseByIndex = exp,
                            incomeByIndex = inc,
                        )
                    }
                }

            fun totalOf(
                target: TxnType,
                list: List<Txn>,
            ) = list.filter { it.type == target }.sumOf { it.amount }

            return ReportUiState(
                window = w,
                type = ty,
                view = viewMode,
                summary =
                    ReportSummary(
                        expense = expense,
                        income = income,
                        count = ofType.size,
                        ranks = ranks,
                        trend = trend,
                        dailyAverage = ReportMath.dailyAverage(totalOf(ty, counted), days.size.coerceAtLeast(1)),
                        expenseChange = ReportMath.changePercent(expense, totalOf(TxnType.EXPENSE, prevCounted)),
                        incomeChange = ReportMath.changePercent(income, totalOf(TxnType.INCOME, prevCounted)),
                    ),
                loading = false,
            )
        }

        private fun windowDays(w: ReportWindow): List<LocalDate> =
            when (w.period) {
                ReportPeriod.MONTH ->
                    (1..w.anchor.lengthOfMonth()).map { LocalDate.of(w.anchor.year, w.anchor.monthValue, it) }
                ReportPeriod.WEEK -> {
                    val monday = w.anchor.minusDays(((w.anchor.dayOfWeek.value + 6) % 7).toLong())
                    (0L..6L).map { monday.plusDays(it) }
                }
                ReportPeriod.YEAR -> emptyList()
            }

        fun setPeriod(period: ReportPeriod) {
            window.value = window.value.withPeriod(period)
        }

        fun setType(value: TxnType) {
            type.value = value
        }

        fun setView(value: ReportView) {
            view.value = value
        }

        fun previous() {
            window.value = window.value.shift(-1)
        }

        fun next() {
            window.value = window.value.shift(1)
        }

        /** 年度总结（AC-42），实际计算在 [YearSummaryUseCase]，两个页面共用。 */
        suspend fun yearSummary(year: Int): YearSummary = yearSummaryUseCase(year)
    }
