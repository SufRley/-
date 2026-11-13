package com.xxjz.ledger.feature.reimbursement

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xxjz.ledger.core.common.AppResult
import com.xxjz.ledger.core.common.DateUtils
import com.xxjz.ledger.core.common.MoneyFormatter
import com.xxjz.ledger.domain.model.Category
import com.xxjz.ledger.domain.model.Txn
import com.xxjz.ledger.domain.repository.CategoryRepository
import com.xxjz.ledger.domain.repository.TxnRepository
import com.xxjz.ledger.domain.usecase.ReimburseTxnUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** P26 待报销行。 */
data class ReimbursementRow(
    val txn: Txn,
    val categoryName: String,
    val dateLabel: String,
)

data class ReimbursementState(
    val pending: List<ReimbursementRow> = emptyList(),
    val done: List<ReimbursementRow> = emptyList(),
    val pendingTotal: Long = 0,
    val message: String? = null,
)

/** P26 待报销（AC-59 ~ AC-62）。 */
@HiltViewModel
class ReimbursementViewModel
    @Inject
    constructor(
        private val txnRepository: TxnRepository,
        private val categoryRepository: CategoryRepository,
        private val reimburse: ReimburseTxnUseCase,
    ) : ViewModel() {
        private val _state = MutableStateFlow(ReimbursementState())
        val state: StateFlow<ReimbursementState> = _state.asStateFlow()

        init {
            refresh()
            viewModelScope.launch {
                txnRepository.observePendingReimbursement().collect { refresh() }
            }
        }

        private fun refresh() {
            viewModelScope.launch {
                val categories = categoryRepository.listAll().associateBy { it.id }
                val pending = txnRepository.pendingReimbursement()
                val done =
                    txnRepository.listInRange(0, Long.MAX_VALUE)
                        .filter { it.reimbursedAt != null && it.type == com.xxjz.ledger.domain.model.TxnType.EXPENSE }
                        .sortedByDescending { it.reimbursedAt }
                        .take(50)
                _state.update { s ->
                    s.copy(
                        pending = pending.map { it.toRow(categories) },
                        done = done.map { it.toRow(categories) },
                        pendingTotal = pending.sumOf { it.amount },
                    )
                }
            }
        }

        private fun Txn.toRow(categories: Map<String, Category>): ReimbursementRow =
            ReimbursementRow(
                txn = this,
                categoryName = categories[categoryId]?.name ?: "未分类",
                dateLabel = DateUtils.friendlyDate(DateUtils.toDate(dateTime)),
            )

        /** 确认报销：填实际到账金额（元）。 */
        fun confirm(
            txnId: String,
            amountText: String,
        ) {
            val cents = MoneyFormatter.yuanToCents(amountText)
            if (cents == null || cents <= 0) {
                _state.update { it.copy(message = "请输入有效的报销金额") }
                return
            }
            viewModelScope.launch {
                when (val result = reimburse.confirm(txnId, cents)) {
                    is AppResult.Success ->
                        _state.update {
                            it.copy(
                                message =
                                    if (result.data.selfPaid > 0) {
                                        "已报销 ¥${MoneyFormatter.centsToYuan(
                                            result.data.reimbursed,
                                        )}，自付 ¥${MoneyFormatter.centsToYuan(result.data.selfPaid)}"
                                    } else {
                                        "已全额报销 ¥${MoneyFormatter.centsToYuan(result.data.reimbursed)}"
                                    },
                            )
                        }
                    is AppResult.Failure -> _state.update { it.copy(message = result.error.message) }
                }
                refresh()
            }
        }

        /** 撤销报销（AC-62）。 */
        fun undo(txnId: String) {
            viewModelScope.launch {
                when (val result = reimburse.undo(txnId)) {
                    is AppResult.Success -> _state.update { it.copy(message = "已撤销报销") }
                    is AppResult.Failure -> _state.update { it.copy(message = result.error.message) }
                }
                refresh()
            }
        }

        fun consumeMessage() = _state.update { it.copy(message = null) }

        /** 首次进入时把历史待报销账目刷新一遍（供无 Flow 的调用点使用）。 */
        suspend fun reload() = refresh()
    }

/** 供首页胶囊使用：待报销笔数与合计。 */
suspend fun pendingReimbursementOf(repository: TxnRepository): Pair<Int, Long> {
    val list = repository.observePendingReimbursement().first()
    return list.size to list.sumOf { it.amount }
}
