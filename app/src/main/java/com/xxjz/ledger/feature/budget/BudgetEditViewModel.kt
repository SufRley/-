package com.xxjz.ledger.feature.budget

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xxjz.ledger.core.common.AppResult
import com.xxjz.ledger.core.common.DateUtils
import com.xxjz.ledger.core.common.MoneyFormatter
import com.xxjz.ledger.domain.model.BudgetPeriod
import com.xxjz.ledger.domain.model.BudgetScope
import com.xxjz.ledger.domain.model.Category
import com.xxjz.ledger.domain.model.TxnType
import com.xxjz.ledger.domain.repository.BudgetRepository
import com.xxjz.ledger.domain.repository.CategoryRepository
import com.xxjz.ledger.domain.usecase.BudgetDraft
import com.xxjz.ledger.domain.usecase.DeleteBudgetUseCase
import com.xxjz.ledger.domain.usecase.GetBudgetForMonthUseCase
import com.xxjz.ledger.domain.usecase.SaveBudgetUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** P11 预算编辑页状态。 */
data class BudgetEditState(
    val monthKey: Int = 0,
    val monthLabel: String = "",
    val name: String = "",
    val period: BudgetPeriod = BudgetPeriod.MONTH,
    val amountText: String = "",
    val scope: BudgetScope = BudgetScope.TOTAL,
    val selected: Set<String> = emptySet(),
    val enabled: Boolean = true,
    /** 大类 → 小类，供多选。 */
    val categories: List<Pair<Category, List<Category>>> = emptyList(),
    val isNew: Boolean = true,
    val error: String? = null,
    val finished: Boolean = false,
) {
    /** 金额是否可保存（元 → 分换算成功）。 */
    val amountCents: Long? get() = MoneyFormatter.yuanToCents(amountText)

    val canSave: Boolean
        get() =
            (amountCents ?: 0L) > 0 &&
                !MoneyFormatter.exceedsLimit(amountCents ?: 0L) &&
                (scope == BudgetScope.TOTAL || selected.isNotEmpty())
}

/** P11 预算编辑（AC-44 / AC-45）。 */
@HiltViewModel
class BudgetEditViewModel
    @Inject
    constructor(
        private val repository: BudgetRepository,
        private val categoryRepository: CategoryRepository,
        private val getBudgetForMonth: GetBudgetForMonthUseCase,
        private val saveBudget: SaveBudgetUseCase,
        private val deleteBudget: DeleteBudgetUseCase,
        savedState: SavedStateHandle,
    ) : ViewModel() {
        private val budgetId: String? = savedState.get<String>("budgetId")?.takeIf { it.isNotBlank() }
        private val monthKey: Int = savedState.get<Int>("monthKey") ?: DateUtils.monthKeyOf(DateUtils.today())

        private val _state = MutableStateFlow(BudgetEditState(monthKey = monthKey, monthLabel = BudgetViewModel.monthLabel(monthKey)))
        val state: StateFlow<BudgetEditState> = _state.asStateFlow()

        init {
            viewModelScope.launch {
                val tree = categoryRepository.tree(TxnType.EXPENSE)
                val existing = budgetId?.let { id -> getBudgetForMonth(monthKey).firstOrNull { it.budget.id == id }?.budget }
                _state.update { s ->
                    s.copy(
                        categories = tree,
                        isNew = existing == null,
                        name = existing?.name ?: s.name,
                        period = existing?.period ?: s.period,
                        amountText = existing?.let { BudgetViewModel.amountText(it.amount) } ?: s.amountText,
                        scope = existing?.scope ?: s.scope,
                        selected = existing?.categoryIds?.toSet() ?: s.selected,
                        enabled = existing?.enabled ?: s.enabled,
                    )
                }
            }
        }

        fun setName(value: String) = _state.update { it.copy(name = value.take(12), error = null) }

        fun setPeriod(value: BudgetPeriod) = _state.update { it.copy(period = value) }

        fun setAmount(value: String) =
            _state.update {
                it.copy(amountText = value.filter { c -> c.isDigit() || c == '.' }.take(12), error = null)
            }

        fun setScope(value: BudgetScope) = _state.update { it.copy(scope = value, error = null) }

        fun toggleEnabled() = _state.update { it.copy(enabled = !it.enabled) }

        /** 选中/取消一个分类；选中小类会带上它的大类，避免界面与实际统计范围不一致。 */
        fun toggleCategory(id: String) {
            _state.update { s ->
                val selected = s.selected.toMutableSet()
                if (!selected.remove(id)) {
                    selected.add(id)
                    s.categories.firstOrNull { it.first.id == id }?.let { selected.add(it.first.id) }
                }
                s.copy(selected = selected, error = null)
            }
        }

        fun save() {
            val s = _state.value
            val amount = s.amountCents
            if (amount == null || amount <= 0) {
                _state.update { it.copy(error = "请输入预算金额") }
                return
            }
            if (s.scope == BudgetScope.CATEGORY && s.selected.isEmpty()) {
                _state.update { it.copy(error = "请选择要纳入预算的分类") }
                return
            }
            viewModelScope.launch {
                val result =
                    saveBudget(
                        BudgetDraft(
                            id = budgetId,
                            name = s.name,
                            period = s.period,
                            scope = s.scope,
                            categoryIds = s.selected.toList(),
                            amountCents = amount,
                            enabled = s.enabled,
                        ),
                        monthKey = monthKey,
                    )
                when (result) {
                    is AppResult.Success -> _state.update { it.copy(finished = true) }
                    is AppResult.Failure -> _state.update { it.copy(error = result.error.message) }
                }
            }
        }

        fun delete() {
            val id = budgetId ?: return
            viewModelScope.launch {
                deleteBudget(id, monthKey)
                _state.update { it.copy(finished = true) }
            }
        }
    }
