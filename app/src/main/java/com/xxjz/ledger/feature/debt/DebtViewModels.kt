package com.xxjz.ledger.feature.debt

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xxjz.ledger.core.common.AppResult
import com.xxjz.ledger.core.common.DateUtils
import com.xxjz.ledger.core.common.MoneyFormatter
import com.xxjz.ledger.domain.model.Debt
import com.xxjz.ledger.domain.model.DebtDirection
import com.xxjz.ledger.domain.model.Txn
import com.xxjz.ledger.domain.repository.DebtRepository
import com.xxjz.ledger.domain.repository.TxnRepository
import com.xxjz.ledger.domain.usecase.DebtDraft
import com.xxjz.ledger.domain.usecase.MarkDebtBadUseCase
import com.xxjz.ledger.domain.usecase.RepayDebtUseCase
import com.xxjz.ledger.domain.usecase.SaveDebtUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

/** 借贷列表分组。 */
data class DebtGroup(
    val lend: List<Debt> = emptyList(),
    val borrow: List<Debt> = emptyList(),
    val settled: List<Debt> = emptyList(),
) {
    /** 应收（借出未还 + 坏账不计）。 */
    val receivable: Long get() = lend.filter { !it.writtenOff }.sumOf { it.remaining }

    /** 应付（借入未还）。 */
    val payable: Long get() = borrow.filter { !it.writtenOff }.sumOf { it.remaining }

    val empty: Boolean get() = lend.isEmpty() && borrow.isEmpty() && settled.isEmpty()
}

data class DebtListState(
    val group: DebtGroup = DebtGroup(),
    val showSettled: Boolean = false,
    val message: String? = null,
)

/** P18 借贷台账（AC-63、AC-66、AC-67）。 */
@HiltViewModel
class DebtListViewModel
    @Inject
    constructor(
        private val debtRepository: DebtRepository,
        private val saveDebt: SaveDebtUseCase,
    ) : ViewModel() {
        private val _state = MutableStateFlow(DebtListState())
        val state: StateFlow<DebtListState> = _state.asStateFlow()

        init {
            viewModelScope.launch {
                debtRepository.observeAll().collect { list ->
                    _state.update {
                        it.copy(
                            group =
                                DebtGroup(
                                    lend = list.filter { d -> d.direction == DebtDirection.LEND && !d.settled && !d.writtenOff },
                                    borrow = list.filter { d -> d.direction == DebtDirection.BORROW && !d.settled && !d.writtenOff },
                                    settled = list.filter { d -> d.settled || d.writtenOff },
                                ),
                        )
                    }
                }
            }
        }

        fun toggleSettled() = _state.update { it.copy(showSettled = !it.showSettled) }

        /** 新建借贷（金额单位：元）。 */
        fun create(
            direction: DebtDirection,
            counterparty: String,
            amountText: String,
            dueDate: LocalDate?,
            note: String,
        ) {
            val cents = MoneyFormatter.yuanToCents(amountText)
            if (cents == null || cents <= 0) {
                _state.update { it.copy(message = "请输入有效金额") }
                return
            }
            if (counterparty.isBlank()) {
                _state.update { it.copy(message = "请填写对方") }
                return
            }
            viewModelScope.launch {
                val result =
                    saveDebt(
                        DebtDraft(
                            direction = direction,
                            counterparty = counterparty,
                            principalCents = cents,
                            startAt = DateUtils.toMillis(DateUtils.today()),
                            dueAt = dueDate?.let { DateUtils.toMillis(it, java.time.LocalTime.of(23, 59)) },
                            note = note,
                        ),
                    )
                when (result) {
                    is AppResult.Success -> _state.update { it.copy(message = "已记录") }
                    is AppResult.Failure -> _state.update { it.copy(message = result.error.message) }
                }
            }
        }

        fun consumeMessage() = _state.update { it.copy(message = null) }
    }

/** P19 借贷详情状态。 */
data class DebtDetailState(
    val debt: Debt? = null,
    val repayments: List<Txn> = emptyList(),
    /** 逾期（未结清且过还款日）。 */
    val overdue: Boolean = false,
    val message: String? = null,
    val closed: Boolean = false,
)

/** P19 借贷详情 + 记还款（AC-64、AC-65）。 */
@HiltViewModel
class DebtDetailViewModel
    @Inject
    constructor(
        private val debtRepository: DebtRepository,
        private val txnRepository: TxnRepository,
        private val repayDebt: RepayDebtUseCase,
        private val markBad: MarkDebtBadUseCase,
        savedState: SavedStateHandle,
    ) : ViewModel() {
        private val debtId: String = savedState.get<String>("debtId").orEmpty()

        private val _state = MutableStateFlow(DebtDetailState())
        val state: StateFlow<DebtDetailState> = _state.asStateFlow()

        init {
            reload()
        }

        private fun reload() {
            viewModelScope.launch {
                val debt = debtRepository.byId(debtId)
                val repayments =
                    txnRepository.listInRange(0, Long.MAX_VALUE)
                        .filter { it.debtId == debtId && !it.isDeleted }
                        .sortedByDescending { it.dateTime }
                _state.update {
                    it.copy(
                        debt = debt,
                        repayments = repayments,
                        overdue = debt?.isOverdue() == true,
                    )
                }
            }
        }

        /** 记一笔还款（元）。 */
        fun repay(amountText: String) {
            val cents = MoneyFormatter.yuanToCents(amountText)
            if (cents == null || cents <= 0) {
                _state.update { it.copy(message = "请输入有效金额") }
                return
            }
            viewModelScope.launch {
                when (val result = repayDebt(debtId, cents)) {
                    is AppResult.Success -> {
                        _state.update {
                            it.copy(message = if (result.data.settled) "已结清" else "已还 ¥${MoneyFormatter.centsToYuan(cents)}")
                        }
                        reload()
                    }
                    is AppResult.Failure -> _state.update { it.copy(message = result.error.message) }
                }
            }
        }

        fun markBad() {
            viewModelScope.launch {
                markBad.mark(debtId)
                _state.update { it.copy(message = "已标记坏账") }
                reload()
            }
        }

        fun remove() {
            viewModelScope.launch {
                debtRepository.delete(debtId)
                _state.update { it.copy(closed = true) }
            }
        }

        fun consumeMessage() = _state.update { it.copy(message = null) }
    }
