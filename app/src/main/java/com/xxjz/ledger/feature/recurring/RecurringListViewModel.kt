package com.xxjz.ledger.feature.recurring

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xxjz.ledger.core.common.DateUtils
import com.xxjz.ledger.domain.model.Category
import com.xxjz.ledger.domain.model.RecurringRule
import com.xxjz.ledger.domain.repository.CategoryRepository
import com.xxjz.ledger.domain.repository.RecurringRepository
import com.xxjz.ledger.domain.usecase.RunRecurringUseCase
import com.xxjz.ledger.domain.usecase.ToggleRecurringUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** P16 列表行。 */
data class RecurringRow(
    val rule: RecurringRule,
    val status: RecurringStatus,
    val title: String,
    val periodLabel: String,
    val nextRunLabel: String,
    val categoryName: String,
)

data class RecurringListState(
    val rows: List<RecurringRow> = emptyList(),
    val activeCount: Int = 0,
    val loading: Boolean = true,
    /** 一次性提示（如「已生成 3 笔」）。 */
    val message: String? = null,
)

/** P16 周期任务列表（AC-53、AC-54、AC-57）。 */
@HiltViewModel
class RecurringListViewModel
    @Inject
    constructor(
        private val recurringRepository: RecurringRepository,
        private val categoryRepository: CategoryRepository,
        private val runRecurring: RunRecurringUseCase,
        private val toggleRecurring: ToggleRecurringUseCase,
    ) : ViewModel() {
        private val _state = MutableStateFlow(RecurringListState())
        val state: StateFlow<RecurringListState> = _state.asStateFlow()

        init {
            viewModelScope.launch {
                runCatching { runRecurring() }.onSuccess { created ->
                    if (created > 0) _state.update { it.copy(message = "已自动记账 $created 笔") }
                }
                val categories = categoryRepository.listAll().associateBy { it.id }
                recurringRepository.observeAll().collect { rules ->
                    _state.update { s ->
                        s.copy(
                            rows = rules.map { it.toRow(categories) },
                            activeCount = rules.count { RecurringCalc.statusOf(it) == RecurringStatus.ACTIVE },
                            loading = false,
                        )
                    }
                }
            }
        }

        private fun RecurringRule.toRow(categories: Map<String, Category>): RecurringRow {
            val title = note.ifBlank { categories[categoryId]?.name ?: "周期记账" }
            return RecurringRow(
                rule = this,
                status = RecurringCalc.statusOf(this),
                title = title,
                periodLabel = RecurringCalc.periodLabel(this),
                nextRunLabel = RecurringCalc.nextRunLabel(this),
                categoryName = categories[categoryId]?.name.orEmpty(),
            )
        }

        /** 暂停 / 恢复（AC-54）。 */
        fun toggle(id: String) {
            viewModelScope.launch { toggleRecurring.toggle(id) }
        }

        /** 立即执行：手动补一笔并前移游标。 */
        fun runNow(id: String) {
            viewModelScope.launch {
                val result = runRecurring.runNow(id)
                _state.update { it.copy(message = if (result is com.xxjz.ledger.core.common.AppResult.Success) "已生成一笔账目" else "执行失败") }
            }
        }

        fun delete(id: String) {
            viewModelScope.launch {
                recurringRepository.delete(id)
                _state.update { it.copy(message = "已删除周期任务") }
            }
        }

        fun consumeMessage() = _state.update { it.copy(message = null) }
    }

/** 供编辑页复用：默认开始时间取今天 09:00。 */
fun defaultRecurringStartAt(): Long = DateUtils.toMillis(DateUtils.today(), java.time.LocalTime.of(9, 0))
