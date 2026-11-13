package com.xxjz.ledger.feature.ledger

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xxjz.ledger.core.common.DateUtils
import com.xxjz.ledger.data.datastore.SettingsRepository
import com.xxjz.ledger.data.datastore.needsBackupReminder
import com.xxjz.ledger.domain.model.Template
import com.xxjz.ledger.domain.repository.CategoryRepository
import com.xxjz.ledger.domain.repository.TemplateRepository
import com.xxjz.ledger.domain.repository.TxnRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.YearMonth
import javax.inject.Inject

/** 明细首页状态。 */
data class LedgerUiState(
    val monthKey: Int = 0,
    val monthLabel: String = "",
    val income: Long = 0,
    val expense: Long = 0,
    val groups: List<DayGroup> = emptyList(),
    val templates: List<Template> = emptyList(),
    val loading: Boolean = true,
    /** 超过 7 天没备份时在首页顶部提示（M8-T8）。 */
    val backupHint: String? = null,
) {
    val balance: Long get() = income - expense
    val count: Int get() = groups.sumOf { it.rows.size }

    /** 本月已过天数与日均支出（用于小结条）。 */
    val elapsedDays: Int get() = DateUtils.elapsedDaysInMonth()
    val dailyAverage: Long get() = if (elapsedDays <= 0) 0 else expense / elapsedDays
}

/** 明细首页 ViewModel：按月订阅流水 + 分类，组装分组与月小计（AC-26）。 */
@HiltViewModel
class LedgerViewModel
    @Inject
    constructor(
        private val txnRepository: TxnRepository,
        private val categoryRepository: CategoryRepository,
        private val templateRepository: TemplateRepository,
        private val settings: SettingsRepository,
    ) : ViewModel() {
        private val month = MutableStateFlow(YearMonth.now())

        private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
        val messages: SharedFlow<String> = _messages.asSharedFlow()

        @OptIn(ExperimentalCoroutinesApi::class)
        val state: StateFlow<LedgerUiState> =
            month
                .flatMapLatest { ym ->
                    val range = DateUtils.monthRange(ym.year, ym.monthValue)
                    combine(
                        txnRepository.observeInRange(range.first, range.last),
                        categoryRepository.observeAll(),
                        templateRepository.observeAll(),
                        settings.settings,
                    ) { txns, categories, templates, pref ->
                        val map = categories.associateBy { it.id }
                        val groups = LedgerGrouper.group(txns, map)
                        val counted = txns.filter { !it.excludedFromStats }
                        LedgerUiState(
                            monthKey = DateUtils.monthKeyOf(ym.atDay(1)),
                            monthLabel = "${ym.year}年${ym.monthValue}月",
                            income = counted.filter { it.type == com.xxjz.ledger.domain.model.TxnType.INCOME }.sumOf { it.amount },
                            expense = counted.filter { it.type == com.xxjz.ledger.domain.model.TxnType.EXPENSE }.sumOf { it.amount },
                            groups = groups,
                            templates = templates,
                            loading = false,
                            backupHint =
                                if (needsBackupReminder(pref.backupReminderEnabled, pref.lastBackupAt, System.currentTimeMillis())) {
                                    if (pref.lastBackupAt <= 0) "还没有备份过，建议去「我的 → 备份与恢复」导出一份" else "距上次备份已超过 7 天"
                                } else {
                                    null
                                },
                        )
                    }
                }.stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(5_000),
                    initialValue =
                        LedgerUiState(
                            monthKey = DateUtils.monthKeyOf(month.value.atDay(1)),
                            monthLabel = "${month.value.year}年${month.value.monthValue}月",
                        ),
                )

        fun selectMonth(
            year: Int,
            monthValue: Int,
        ) {
            month.value = YearMonth.of(year, monthValue)
        }

        fun previousMonth() {
            month.value = month.value.minusMonths(1)
        }

        fun nextMonth() {
            month.value = month.value.plusMonths(1)
        }

        fun delete(id: String) {
            viewModelScope.launch {
                txnRepository.softDelete(id, System.currentTimeMillis())
                _messages.emit("已移入回收站")
            }
        }
    }
