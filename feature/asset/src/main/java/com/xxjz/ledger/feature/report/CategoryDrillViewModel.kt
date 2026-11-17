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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import javax.inject.Inject

/** 下钻页里的一笔流水。 */
data class DrillRow(
    val txn: Txn,
    val categoryName: String,
)

/** P08 分类下钻状态。 */
data class DrillUiState(
    val categoryId: String = "",
    val title: String = "分类",
    val breadcrumb: List<String> = emptyList(),
    val monthLabel: String = "",
    val total: Long = 0,
    val prevTotal: Long = 0,
    val changePercent: Int? = null,
    /** 二级分类分解（点大类进来时才有）。 */
    val children: List<RankItem> = emptyList(),
    val rows: List<DrillRow> = emptyList(),
    val loading: Boolean = true,
)

/**
 * 分类下钻（AC-38 / AC-39）。
 *
 * 口径与 P07 一致：本月至今 vs 上月同期，避免月中永远显示「跌」。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CategoryDrillViewModel
    @Inject
    constructor(
        private val txnRepository: TxnRepository,
        private val categoryRepository: CategoryRepository,
    ) : ViewModel() {
        private val categoryId = MutableStateFlow("")
        private val type = MutableStateFlow(TxnType.EXPENSE)

        val state: StateFlow<DrillUiState> =
            combine(categoryId, type, categoryRepository.observeAll()) { id, t, cats -> Triple(id, t, cats) }
                .map { (id, t, cats) -> build(id, t, cats, DateUtils.today()) }
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(5_000),
                    initialValue = DrillUiState(),
                )

        private suspend fun build(
            id: String,
            ty: TxnType,
            cats: List<Category>,
            anchor: LocalDate,
        ): DrillUiState {
            if (id.isBlank()) return DrillUiState(loading = false)
            val nameOf = cats.associate { it.id to it.name }
            val parentOf = cats.associate { it.id to it.parentId }
            val rootId = parentOf[id] ?: id
            val childIds = cats.filter { it.parentId == rootId }.map { it.id }.toSet() + rootId

            val range = DateUtils.monthRange(anchor.year, anchor.monthValue)
            val prevRange =
                DateUtils.monthRange(anchor.year, anchor.monthValue).let {
                    val prev = anchor.minusMonths(1)
                    DateUtils.monthRange(prev.year, prev.monthValue).first..ReportMath.sameSpanEndOfLastMonth(anchor, anchor.dayOfMonth)
                }
            val current =
                txnRepository.listInRange(range.first, range.last)
                    .filter { !it.isDeleted && !it.excludedFromStats && it.type == ty }
            val prev =
                txnRepository.listInRange(prevRange.first, prevRange.last)
                    .filter { !it.isDeleted && !it.excludedFromStats && it.type == ty }

            val mine = current.filter { it.categoryId in childIds }
            val prevMine = prev.filter { it.categoryId in childIds }

            // 二级分解：只展示真正有子类的情况
            val byChild =
                mine.groupBy { it.categoryId ?: "" }
                    .map { (cid, list) -> Triple(cid, nameOf[cid] ?: "未分类", list.sumOf { it.amount }) }
            val childRanks =
                if (byChild.size > 1) {
                    ReportMath.buildRanks(
                        rows = byChild,
                        previous = prevMine.groupBy { it.categoryId ?: "" }.mapValues { e -> e.value.sumOf { it.amount } },
                    )
                } else {
                    emptyList()
                }

            return DrillUiState(
                categoryId = id,
                title = nameOf[id] ?: "分类",
                breadcrumb = listOfNotNull(nameOf[rootId]?.takeIf { rootId != id }, nameOf[id]),
                monthLabel = "${anchor.year}年${anchor.monthValue}月",
                total = mine.sumOf { it.amount },
                prevTotal = prevMine.sumOf { it.amount },
                changePercent = ReportMath.changePercent(mine.sumOf { it.amount }, prevMine.sumOf { it.amount }),
                children = childRanks,
                rows =
                    mine.sortedByDescending { it.dateTime }.map { txn ->
                        DrillRow(txn = txn, categoryName = nameOf[txn.categoryId] ?: "未分类")
                    },
                loading = false,
            )
        }

        fun load(id: String) {
            categoryId.value = id
        }

        fun setType(value: TxnType) {
            type.value = value
        }
    }
