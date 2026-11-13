package com.xxjz.ledger.feature.report

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xxjz.ledger.core.common.DateUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** P09 年度总结的宿主状态（数据计算在 [YearSummaryUseCase]）。 */
@HiltViewModel
class YearSummaryViewModel
    @Inject
    constructor(
        private val yearSummary: YearSummaryUseCase,
    ) : ViewModel() {
        private val _state = MutableStateFlow(YearSummary())
        val state: StateFlow<YearSummary> = _state.asStateFlow()

        private var loaded = false

        /** 首次进入时加载，切年时重算。 */
        fun load(year: Int = DateUtils.today().year) {
            if (loaded && _state.value.year == year) return
            loaded = true
            viewModelScope.launch { _state.value = yearSummary(year) }
        }

        fun previousYear() {
            loaded = false
            load(_state.value.year - 1)
        }

        fun nextYear() {
            loaded = false
            load(_state.value.year + 1)
        }
    }
