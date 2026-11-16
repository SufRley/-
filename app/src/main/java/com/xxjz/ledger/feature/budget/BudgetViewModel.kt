package com.xxjz.ledger.feature.budget

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xxjz.ledger.core.common.DateUtils
import com.xxjz.ledger.core.common.MoneyFormatter
import com.xxjz.ledger.domain.model.Budget
import com.xxjz.ledger.domain.model.BudgetPeriod
import com.xxjz.ledger.domain.model.BudgetScope
import com.xxjz.ledger.domain.model.Category
import com.xxjz.ledger.domain.model.TxnType
import com.xxjz.ledger.domain.repository.BudgetRepository
import com.xxjz.ledger.domain.repository.CategoryRepository
import com.xxjz.ledger.domain.repository.SettingsRepository
import com.xxjz.ledger.domain.repository.TxnRepository
import com.xxjz.ledger.domain.usecase.DeleteBudgetUseCase
import com.xxjz.ledger.domain.usecase.GetBudgetForMonthUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.YearMonth
import javax.inject.Inject

/** 预算首页的整体状态。 */
data class BudgetUiState(
    val monthKey: Int = 0,
    val monthLabel: String = "",
    /** 总预算（范围=全部支出）。 */
    val total: BudgetProgress? = null,
    /** 分类预算，已按「超支置顶 + 使用比例」排序。 */
    val categoryBudgets: List<BudgetProgress> = emptyList(),
    /** 结余目标（分），0 表示未设置。 */
    val savingGoalCents: Long = 0,
    /** 本月结余 = 收入 - 支出。 */
    val monthBalance: Long = 0,
    val monthIncome: Long = 0,
    val monthExpense: Long = 0,
    /** 该月是否配置过预算（决定是否显示空态）。 */
    val hasConfig: Boolean = false,
    /** 配置来源月份，非空表示是继承来的。 */
    val inheritedFrom: Int? = null,
) {
    /** 结余目标完成度，钳在 0..1。 */
    val savingFraction: Float
        get() = if (savingGoalCents <= 0) 0f else (monthBalance.toDouble() / savingGoalCents).toFloat().coerceIn(0f, 1f)

    val savingAchieved: Boolean get() = savingGoalCents > 0 && monthBalance >= savingGoalCents

    /** 分类预算合计（不含停用项），用于卡片小标题。 */
    val categoryTotal: Long get() = categoryBudgets.filter { it.status != BudgetStatus.DISABLED }.sumOf { it.budget.amount }

    val categoryUsed: Long get() = categoryBudgets.filter { it.status != BudgetStatus.DISABLED }.sumOf { it.used }
}

/** P10 预算首页。 */
@HiltViewModel
class BudgetViewModel
    @Inject
    constructor(
        private val budgetRepository: BudgetRepository,
        private val categoryRepository: CategoryRepository,
        private val txnRepository: TxnRepository,
        private val getBudgetForMonth: GetBudgetForMonthUseCase,
        private val deleteBudget: DeleteBudgetUseCase,
        private val settings: SettingsRepository,
    ) : ViewModel() {
        private val month = MutableStateFlow(DateUtils.monthKeyOf(DateUtils.today()))

        /** 当前查看的月份，供编辑页跳转时带上。 */
        val monthFlow: StateFlow<Int> = month.asStateFlow()

        @OptIn(ExperimentalCoroutinesApi::class)
        val state: StateFlow<BudgetUiState> =
            month
                .flatMapLatest { monthKey -> observeMonth(monthKey) }
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(5_000),
                    initialValue =
                        BudgetUiState(
                            monthKey = DateUtils.monthKeyOf(DateUtils.today()),
                            monthLabel = monthLabel(DateUtils.monthKeyOf(DateUtils.today())),
                        ),
                )

        private fun observeMonth(monthKey: Int) =
            combine(
                getBudgetForMonth.observe(monthKey),
                categoryRepository.observeAll(),
                txnRepository.observeInRange(
                    DateUtils.yearRange(monthKey / 100).first,
                    DateUtils.yearRange(monthKey / 100).last,
                ),
                settings.settings,
            ) { effective, categories, txns, prefs ->
                val today = DateUtils.today()
                val inherited = effective.firstOrNull { it.inheritedFrom != null }?.inheritedFrom
                val progresses =
                    effective.map { item ->
                        val budget = item.budget
                        val used = BudgetCalc.usedOf(budget, txns, categories, monthKey = monthKey)
                        BudgetCalc.progress(
                            budget = budget,
                            used = used,
                            monthKey = monthKey,
                            today = today,
                            categoryLabel = labelOf(budget, categories),
                            inherited = item.inheritedFrom != null,
                        )
                    }
                val total = progresses.firstOrNull { it.budget.scope == BudgetScope.TOTAL }
                val monthRange = DateUtils.monthRange(monthKey / 100, monthKey % 100)
                val counted =
                    txns.filter {
                        !it.isDeleted && !it.excludedFromStats && it.dateTime in monthRange.first..monthRange.last
                    }
                val income = counted.filter { it.type == TxnType.INCOME }.sumOf { it.amount }
                val expense = counted.filter { it.type == TxnType.EXPENSE }.sumOf { it.amount }
                BudgetUiState(
                    monthKey = monthKey,
                    monthLabel = monthLabel(monthKey),
                    total = total,
                    categoryBudgets = BudgetCalc.sort(progresses.filter { it.budget.scope == BudgetScope.CATEGORY }),
                    savingGoalCents = prefs.savingGoalCents,
                    monthBalance = income - expense,
                    monthIncome = income,
                    monthExpense = expense,
                    hasConfig = effective.isNotEmpty(),
                    inheritedFrom = inherited,
                )
            }

        fun previousMonth() {
            month.value = shift(month.value, -1)
        }

        fun nextMonth() {
            month.value = shift(month.value, 1)
        }

        fun selectMonth(monthKey: Int) {
            month.value = monthKey
        }

        fun backToCurrent() {
            month.value = DateUtils.monthKeyOf(DateUtils.today())
        }

        /** 删除预算：进回收站（AC-50）。 */
        fun remove(id: String) {
            val monthKey = month.value
            viewModelScope.launch { deleteBudget(id, monthKey) }
        }

        /** 启用/停用预算。 */
        fun toggleEnabled(budget: Budget) {
            val monthKey = month.value
            viewModelScope.launch {
                getBudgetForMonth.materialize(monthKey)
                budgetRepository.setEnabled(budget.id, monthKey, !budget.enabled)
            }
        }

        /** 设置结余目标（分），0 表示清除。 */
        fun setSavingGoal(cents: Long) {
            viewModelScope.launch { settings.setSavingGoal(cents) }
        }

        /** 分类预算的可读名称：选中的分类用「、」连接。 */
        private fun labelOf(
            budget: Budget,
            categories: List<Category>,
        ): String {
            if (budget.scope != BudgetScope.CATEGORY) return ""
            val names = categories.associate { it.id to it.name }
            return budget.categoryIds.mapNotNull { names[it] }.distinct().joinToString("、")
        }

        companion object {
            fun shift(
                monthKey: Int,
                delta: Long,
            ): Int {
                val ym = YearMonth.of(monthKey / 100, monthKey % 100).plusMonths(delta)
                return ym.year * 100 + ym.monthValue
            }

            fun monthLabel(monthKey: Int): String = "${monthKey / 100}年${monthKey % 100}月"

            /** 编辑页默认金额文案（元）。 */
            fun amountText(cents: Long): String = if (cents <= 0) "" else MoneyFormatter.centsToPlain(cents)
        }
    }

/** 预算周期的中文名。 */
fun BudgetPeriod.label(): String = if (this == BudgetPeriod.YEAR) "按年" else "按月"
