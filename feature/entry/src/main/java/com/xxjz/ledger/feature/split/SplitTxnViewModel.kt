package com.xxjz.ledger.feature.split

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xxjz.ledger.core.common.MoneyFormatter
import com.xxjz.ledger.domain.model.Account
import com.xxjz.ledger.domain.model.Txn
import com.xxjz.ledger.domain.repository.AccountRepository
import com.xxjz.ledger.domain.repository.TxnRepository
import com.xxjz.ledger.domain.usecase.FundPayable
import com.xxjz.ledger.domain.usecase.SplitPart
import com.xxjz.ledger.domain.usecase.SplitTxnUseCase
import com.xxjz.ledger.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 拆分明细里的一行。 */
data class SplitRow(
    /** 稳定的本地键，用于列表 key 与测试定位；与账户 id 无关，同一账户可以有两行。 */
    val key: String,
    val accountId: String? = null,
    val amountText: String = "",
    /**
     * 是否是「原账户那一行」（v1.9.6 第 3 条）。
     *
     * 它同时是**余数承接者**：用户改别的行时，这一行自动让出金额。
     * 用标记而不是「第一行」来判断，是因为删行、加行之后位置会变。
     */
    val isSource: Boolean = false,
) {
    val cents: Long get() = MoneyFormatter.yuanToCents(amountText) ?: 0L
}

/** 拆分明细页状态。 */
data class SplitUiState(
    val loading: Boolean = true,
    val source: Txn? = null,
    val categoryName: String = "",
    /** 可付款账户（现金 / 储蓄卡 / 信用）。 */
    val accounts: List<Account> = emptyList(),
    val rows: List<SplitRow> = emptyList(),
    val saving: Boolean = false,
    val error: String? = null,
    /** 用户是否动过明细，用于返回时的放弃确认。 */
    val dirty: Boolean = false,
    /**
     * 是否仍由原账户行自动承接余数（v1.9.6 第 3 条）。
     *
     * 用户一旦手动改过原账户行，就交出手动控制权：此后所有行都按手填的值算，
     * 只做差额提示，不再自动覆盖。否则用户改完原账户、再去填第二行，原账户又被改回去，
     * 看起来像「我的输入被吃掉了」。
     */
    val autoBalance: Boolean = true,
) {
    val sourceCents: Long get() = source?.amount ?: 0L

    val totalCents: Long get() = rows.sumOf { it.cents }

    /** 与原始金额的差额：正数表示超出，负数表示还差。 */
    val diffCents: Long get() = totalCents - sourceCents

    val canAdd: Boolean get() = rows.size < SplitTxnUseCase.MAX_PARTS

    val canRemove: Boolean get() = rows.size > SplitTxnUseCase.MIN_PARTS

    /**
     * 保存前置检查：null 表示能存。
     *
     * 合计不等于原额时**不让存**：拆分只是换一种记账方式，总额不能凭空多一分或少一分。
     */
    val blocker: String?
        get() =
            when {
                saving -> "正在保存…"
                source == null -> "原账目不存在"
                rows.size < SplitTxnUseCase.MIN_PARTS -> "至少要两条明细"
                rows.any { it.accountId == null } -> "每条明细都要选账户"
                // 差额优先于「金额大于 0」：其他行填多了会把原账户行压成 0，
                // 这时用户该看到的是「超出多少」，而不是「有一条是 0」——
                // 后者是他填出来的结果，前者才是需要他改的事
                diffCents > 0 -> "超出 ¥${MoneyFormatter.centsToYuan(diffCents)}"
                diffCents < 0 -> "还差 ¥${MoneyFormatter.centsToYuan(-diffCents)}"
                rows.any { it.cents <= 0L } -> "每条明细的金额都要大于 0"
                else -> null
            }
}

/** P27 拆分明细（v1.9.5 第 8 条）。 */
@HiltViewModel
class SplitTxnViewModel
    @Inject
    constructor(
        private val txnRepository: TxnRepository,
        private val accountRepository: AccountRepository,
        private val splitTxn: SplitTxnUseCase,
        savedStateHandle: SavedStateHandle,
    ) : ViewModel() {
        private val txnId: String = savedStateHandle.get<String>(Routes.ARG_TXN_ID).orEmpty()

        private val _state = MutableStateFlow(SplitUiState())
        val state: StateFlow<SplitUiState> = _state.asStateFlow()

        private val _events = MutableSharedFlow<String>(extraBufferCapacity = 2)
        val events: SharedFlow<String> = _events.asSharedFlow()

        private var seq = 0

        init {
            load()
            viewModelScope.launch {
                accountRepository.observeWithBalances().collect { list ->
                    _state.update { it.copy(accounts = FundPayable.payableAccounts(list)) }
                }
            }
        }

        /**
         * 载入原账并预置两行。
         *
         * 第一行预填「原账户 + 原金额」：绝大多数拆分是「这张卡出了大部分、另一张补一点」，
         * 用户只要改第二行即可，不必从零填两遍。
         */
        private fun load() {
            viewModelScope.launch {
                val txn = txnRepository.byId(txnId)
                if (txn == null) {
                    _state.update { it.copy(loading = false, error = "原账目不存在") }
                    return@launch
                }
                val category = txn.categoryId
                _state.update {
                    it.copy(
                        loading = false,
                        source = txn,
                        categoryName = category.orEmpty(),
                        rows =
                            listOf(
                                newRow(
                                    accountId = txn.accountId,
                                    amount = MoneyFormatter.centsToPlain(txn.amount),
                                    isSource = true,
                                ),
                                newRow(null, ""),
                            ),
                    )
                }
            }
        }

        private fun newRow(
            accountId: String?,
            amount: String,
            isSource: Boolean = false,
        ): SplitRow {
            seq += 1
            return SplitRow(key = "row_$seq", accountId = accountId, amountText = amount, isSource = isSource)
        }

        fun addRow() {
            if (!state.value.canAdd) return
            _state.update { it.copy(rows = it.rows + newRow(null, ""), error = null, dirty = true) }
        }

        fun removeRow(key: String) {
            if (!state.value.canRemove) return
            _state.update { current ->
                val rest = current.rows.filterNot { row -> row.key == key }
                current.copy(
                    rows = rest,
                    // 原账户行被删掉之后就没有「余数承接者」了，退回纯手工
                    autoBalance = current.autoBalance && rest.any { it.isSource },
                    error = null,
                    dirty = true,
                )
            }
        }

        fun setAccount(
            key: String,
            accountId: String,
        ) = _state.update { current ->
            current.copy(
                rows = current.rows.map { if (it.key == key) it.copy(accountId = accountId) else it },
                error = null,
                dirty = true,
            )
        }

        /**
         * 改某一行金额。
         *
         * 改的是**别的**行时，原账户行自动变成「原额 − 其他行合计」——用户只需要填新增的那一行，
         * 不用自己心算余数（v1.9.6 第 3 条）。改原账户行自己不联动，否则就是自己覆盖自己；
         * 此时合计对不上，底部照旧提示「还差 / 超出」。
         */
        fun setAmount(
            key: String,
            text: String,
        ) {
            val filtered = text.filter { it.isDigit() || it == '.' }.take(12)
            _state.update { current ->
                val edited = current.rows.map { if (it.key == key) it.copy(amountText = filtered) else it }
                val editedSource = current.rows.firstOrNull { it.isSource }?.key == key
                current.copy(
                    rows = if (current.autoBalance && !editedSource) rebalance(edited, current.sourceCents) else edited,
                    // 手动改原账户 = 接管：之后不再自动重算，免得把用户刚填的数覆盖掉
                    autoBalance = current.autoBalance && !editedSource,
                    error = null,
                    dirty = true,
                )
            }
        }

        /**
         * 让原账户行承接余数。
         *
         * 其他行合计超过原额时显示 `0.00` 而不是负数：负金额没有意义，超出的部分
         * 由底部「超出 ¥X」提示与保存校验兜底。
         */
        private fun rebalance(
            rows: List<SplitRow>,
            sourceCents: Long,
        ): List<SplitRow> {
            if (rows.none { it.isSource }) return rows
            val others = rows.filterNot { it.isSource }.sumOf { it.cents }
            val rest = (sourceCents - others).coerceAtLeast(0L)
            return rows.map { row ->
                if (row.isSource) row.copy(amountText = MoneyFormatter.centsToPlain(rest)) else row
            }
        }

        fun dismissError() = _state.update { it.copy(error = null) }

        /** 保存：交给用例，成功后发一条事件让页面退出。 */
        fun save() {
            val current = state.value
            if (current.saving) return
            val blocker = current.blocker
            if (blocker != null) {
                _state.update { it.copy(error = blocker) }
                return
            }
            val parts =
                current.rows.mapNotNull { row ->
                    val accountId = row.accountId ?: return@mapNotNull null
                    SplitPart(accountId, row.cents)
                }
            viewModelScope.launch {
                _state.update { it.copy(saving = true, error = null) }
                when (val result = splitTxn(txnId, parts)) {
                    is com.xxjz.ledger.core.common.AppResult.Success -> {
                        _state.update { it.copy(saving = false) }
                        _events.emit("已拆成 ${result.data.ids.size} 笔")
                    }
                    is com.xxjz.ledger.core.common.AppResult.Failure -> {
                        _state.update { it.copy(saving = false, error = result.error.message) }
                    }
                }
            }
        }
    }
