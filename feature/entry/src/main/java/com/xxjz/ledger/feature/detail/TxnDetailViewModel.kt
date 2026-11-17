package com.xxjz.ledger.feature.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xxjz.ledger.core.common.AppResult
import com.xxjz.ledger.domain.model.Txn
import com.xxjz.ledger.domain.repository.AccountRepository
import com.xxjz.ledger.domain.repository.CategoryRepository
import com.xxjz.ledger.domain.repository.TxnRepository
import com.xxjz.ledger.domain.usecase.DuplicateTxnUseCase
import com.xxjz.ledger.domain.usecase.RefundTxnUseCase
import com.xxjz.ledger.domain.usecase.ToggleExcludeUseCase
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
import javax.inject.Inject

/** 详情页一次性事件。 */
sealed interface DetailEvent {
    /** 账目已删除，需要退出详情页。 */
    data object Closed : DetailEvent

    data class Message(val text: String) : DetailEvent
}

/** P02 流水详情状态。 */
data class TxnDetailUiState(
    val txn: Txn? = null,
    val categoryName: String = "",
    val accountName: String = "",
    val toAccountName: String? = null,
    /** 已退款金额（分）。 */
    val refunded: Long = 0,
    val loading: Boolean = true,
) {
    val refundable: Long get() = ((txn?.amount ?: 0) - refunded).coerceAtLeast(0)
}

/** 流水详情：展示 + 编辑入口 + 退款 / 复制 / 不计入收支 / 删除（AC-10 ~ AC-15）。 */
@HiltViewModel
class TxnDetailViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val txnRepository: TxnRepository,
        private val categoryRepository: CategoryRepository,
        private val accountRepository: AccountRepository,
        private val refundTxn: RefundTxnUseCase,
        private val toggleExclude: ToggleExcludeUseCase,
        private val duplicateTxn: DuplicateTxnUseCase,
    ) : ViewModel() {
        private val txnId: String = savedStateHandle.get<String>("txnId").orEmpty()

        private val refresh = MutableStateFlow(0)

        private val _events = MutableSharedFlow<DetailEvent>(extraBufferCapacity = 4)
        val events: SharedFlow<DetailEvent> = _events.asSharedFlow()

        @OptIn(ExperimentalCoroutinesApi::class)
        val state: StateFlow<TxnDetailUiState> =
            refresh
                .flatMapLatest {
                    combine(
                        txnRepository.observeById(txnId),
                        categoryRepository.observeAll(),
                        accountRepository.observeAll(),
                    ) { txn, categories, accounts ->
                        val catMap = categories.associateBy { it.id }
                        val accMap = accounts.associateBy { it.id }
                        val refunded = txn?.let { txnRepository.refundedTotal(it.id) } ?: 0L
                        TxnDetailUiState(
                            txn = txn,
                            categoryName = txn?.categoryId?.let { catMap[it]?.name } ?: "未分类",
                            accountName = txn?.accountId?.let { accMap[it]?.name }.orEmpty(),
                            toAccountName = txn?.toAccountId?.let { accMap[it]?.name },
                            refunded = refunded,
                            loading = false,
                        )
                    }
                }.stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(5_000),
                    initialValue = TxnDetailUiState(),
                )

        fun toggleExclude() {
            viewModelScope.launch {
                when (val r = toggleExclude(txnId)) {
                    is AppResult.Success ->
                        _events.emit(DetailEvent.Message(if (r.data) "已设为不计入收支" else "已恢复计入收支"))
                    is AppResult.Failure -> _events.emit(DetailEvent.Message(r.error.message))
                }
            }
        }

        fun delete() {
            viewModelScope.launch {
                txnRepository.softDelete(txnId, System.currentTimeMillis())
                _events.emit(DetailEvent.Closed)
            }
        }

        fun duplicate() {
            viewModelScope.launch {
                when (val r = duplicateTxn(txnId)) {
                    is AppResult.Success -> _events.emit(DetailEvent.Message("已复制一笔，时间改为现在"))
                    is AppResult.Failure -> _events.emit(DetailEvent.Message(r.error.message))
                }
            }
        }

        fun refund(amountCents: Long) {
            viewModelScope.launch {
                when (val r = refundTxn(txnId, amountCents)) {
                    is AppResult.Success -> {
                        refresh.value += 1
                        _events.emit(DetailEvent.Message("退款成功，已记一笔退款收入"))
                    }
                    is AppResult.Failure -> _events.emit(DetailEvent.Message(r.error.message))
                }
            }
        }
    }
