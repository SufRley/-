package com.xxjz.ledger.feature.calendar

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

/** 日历中的一天。 */
data class DayCell(
    val date: LocalDate?,
    val expense: Long = 0,
    val income: Long = 0,
    val isToday: Boolean = false,
)

data class CalendarRow(
    val txn: Txn,
    val categoryName: String,
)

/** P04 日历页状态。 */
data class CalendarUiState(
    val monthLabel: String = "",
    val weeks: List<List<DayCell>> = emptyList(),
    val selected: LocalDate = DateUtils.today(),
    val dayRows: List<CalendarRow> = emptyList(),
    val monthExpense: Long = 0,
    val loading: Boolean = true,
)

/** 日历视图（AC-32 / AC-33）：月网格 + 每日收支 + 点选当天。 */
@HiltViewModel
class CalendarViewModel
    @Inject
    constructor(
        private val txnRepository: TxnRepository,
        private val categoryRepository: CategoryRepository,
    ) : ViewModel() {
        private val month = MutableStateFlow(YearMonth.now())
        private val selected = MutableStateFlow(DateUtils.today())

        @OptIn(ExperimentalCoroutinesApi::class)
        val state: StateFlow<CalendarUiState> =
            month
                .flatMapLatest { ym ->
                    val range = DateUtils.monthRange(ym.year, ym.monthValue)
                    combine(
                        txnRepository.observeInRange(range.first, range.last),
                        categoryRepository.observeAll(),
                        selected,
                    ) { txns, categories, sel -> build(ym, txns, categories, sel) }
                }.stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(5_000),
                    initialValue =
                        CalendarUiState(
                            monthLabel = "${DateUtils.today().year}年${DateUtils.today().monthValue}月",
                        ),
                )

        private fun build(
            ym: YearMonth,
            txns: List<Txn>,
            categories: List<Category>,
            sel: LocalDate,
        ): CalendarUiState {
            val today = DateUtils.today()
            val byDay = txns.filter { !it.excludedFromStats }.groupBy { it.dateKey }
            val cells =
                CalendarGrid.build(ym.year, ym.monthValue).map { cell ->
                    val date = cell.date
                    val list = date?.let { byDay[DateUtils.dateKeyOf(it)] }.orEmpty()
                    DayCell(
                        date = date,
                        expense = list.filter { it.type == TxnType.EXPENSE }.sumOf { it.amount },
                        income = list.filter { it.type == TxnType.INCOME }.sumOf { it.amount },
                        isToday = date == today,
                    )
                }
            val map = categories.associateBy { it.id }
            val dayList =
                txns.filter { it.dateKey == DateUtils.dateKeyOf(sel) }
                    .sortedByDescending { it.dateTime }
                    .map { txn ->
                        CalendarRow(
                            txn = txn,
                            categoryName = txn.categoryId?.let { map[it]?.name } ?: "未分类",
                        )
                    }
            return CalendarUiState(
                monthLabel = "${ym.year}年${ym.monthValue}月",
                weeks = cells.chunked(7),
                selected = sel,
                dayRows = dayList,
                monthExpense = txns.filter { it.type == TxnType.EXPENSE && !it.excludedFromStats }.sumOf { it.amount },
                loading = false,
            )
        }

        fun previousMonth() {
            month.value = month.value.minusMonths(1)
        }

        fun nextMonth() {
            month.value = month.value.plusMonths(1)
        }

        fun select(date: LocalDate) {
            selected.value = date
        }

        fun selectToday() {
            val today = DateUtils.today()
            month.value = YearMonth.from(today)
            selected.value = today
        }
    }
