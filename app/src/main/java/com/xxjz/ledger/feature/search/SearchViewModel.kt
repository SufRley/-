package com.xxjz.ledger.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xxjz.ledger.data.datastore.SettingsRepository
import com.xxjz.ledger.domain.model.Txn
import com.xxjz.ledger.domain.model.TxnType
import com.xxjz.ledger.domain.repository.CategoryRepository
import com.xxjz.ledger.domain.repository.TxnRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 搜索结果行。 */
data class SearchRow(
    val txn: Txn,
    val categoryName: String,
)

/** P03 搜索状态。 */
data class SearchUiState(
    val query: String = "",
    val type: TxnType? = null,
    val range: SearchRangeOption = SearchRangeOption.THIS_MONTH,
    val minText: String = "",
    val maxText: String = "",
    val results: List<SearchRow> = emptyList(),
    val totalCents: Long = 0,
    val history: List<String> = emptyList(),
    val searching: Boolean = false,
) {
    val count: Int get() = results.size
}

private data class Criteria(
    val query: String,
    val type: TxnType?,
    val range: SearchRangeOption,
    val minText: String,
    val maxText: String,
)

/** 关键词检索（AC-28 / AC-29 / AC-31）：300ms 防抖 + 类型/时间/金额筛选 + 历史记录。 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class SearchViewModel
    @Inject
    constructor(
        private val txnRepository: TxnRepository,
        private val categoryRepository: CategoryRepository,
        private val settings: SettingsRepository,
    ) : ViewModel() {
        private val query = MutableStateFlow("")
        private val type = MutableStateFlow<TxnType?>(null)
        private val range = MutableStateFlow(SearchRangeOption.THIS_MONTH)
        private val minText = MutableStateFlow("")
        private val maxText = MutableStateFlow("")

        private val criteria =
            combine(
                query.debounce(300),
                type,
                range,
                minText,
                maxText,
            ) { q, t, r, min, max -> Criteria(q, t, r, min, max) }

        val state: StateFlow<SearchUiState> =
            combine(
                criteria,
                categoryRepository.observeAll(),
                settings.searchHistory,
            ) { c, categories, history ->
                val map = categories.associateBy { it.id }
                val span = SearchFilters.range(c.range)
                val list =
                    txnRepository.search(
                        keyword = c.query,
                        type = c.type,
                        minCents = SearchFilters.minCents(c.minText),
                        maxCents = SearchFilters.maxCents(c.maxText),
                        start = span.first,
                        end = span.last,
                    )
                SearchUiState(
                    query = c.query,
                    type = c.type,
                    range = c.range,
                    minText = c.minText,
                    maxText = c.maxText,
                    results =
                        list.map { txn ->
                            SearchRow(
                                txn = txn,
                                categoryName = txn.categoryId?.let { map[it]?.name } ?: "未分类",
                            )
                        },
                    totalCents = list.sumOf { it.amount },
                    history = history,
                )
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = SearchUiState(),
            )

        fun setQuery(text: String) {
            query.value = text
        }

        fun submitQuery() {
            val kw = query.value.trim()
            if (kw.isNotEmpty()) viewModelScope.launch { settings.addSearchKeyword(kw) }
        }

        fun setType(value: TxnType?) {
            type.value = value
        }

        fun setRange(value: SearchRangeOption) {
            range.value = value
        }

        fun setMin(text: String) {
            minText.value = text.filter { it.isDigit() || it == '.' }.take(12)
        }

        fun setMax(text: String) {
            maxText.value = text.filter { it.isDigit() || it == '.' }.take(12)
        }

        fun clearFilters() {
            type.value = null
            range.value = SearchRangeOption.THIS_MONTH
            minText.value = ""
            maxText.value = ""
        }

        fun clearHistory() {
            viewModelScope.launch { settings.clearSearchHistory() }
        }
    }
