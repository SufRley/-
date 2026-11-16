package com.xxjz.ledger.feature.reimbursement

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xxjz.ledger.core.common.AppResult
import com.xxjz.ledger.core.common.DateUtils
import com.xxjz.ledger.core.common.MoneyFormatter
import com.xxjz.ledger.domain.model.Account
import com.xxjz.ledger.domain.model.Category
import com.xxjz.ledger.domain.model.Txn
import com.xxjz.ledger.domain.repository.AccountRepository
import com.xxjz.ledger.domain.repository.CategoryRepository
import com.xxjz.ledger.domain.repository.TxnRepository
import com.xxjz.ledger.domain.usecase.FundPayable
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

/** 待报销的一天：组头文案 + 组内条目 + 组合计（v1.9.4 第 5 条）。 */
data class PendingGroup(
    val dateKey: Int,
    val dateLabel: String,
    val rows: List<ReimbursementRow>,
    val total: Long,
)

data class ReimbursementState(
    val pending: List<ReimbursementRow> = emptyList(),
    val done: List<ReimbursementRow> = emptyList(),
    val pendingTotal: Long = 0,
    val message: String? = null,
    /** 可收报销款的账户（现金 / 储蓄卡 / 信用），按 sortOrder 排（v1.9.5 第 3 条收掉投资）。 */
    val fundingAccounts: List<Account> = emptyList(),
)

/** P26 待报销（AC-59 ~ AC-62）。 */
@HiltViewModel
class ReimbursementViewModel
    @Inject
    constructor(
        private val txnRepository: TxnRepository,
        private val categoryRepository: CategoryRepository,
        private val reimburse: ReimburseTxnUseCase,
        private val accountRepository: AccountRepository,
    ) : ViewModel() {
        private val _state = MutableStateFlow(ReimbursementState())
        val state: StateFlow<ReimbursementState> = _state.asStateFlow()

        init {
            refresh()
            viewModelScope.launch {
                txnRepository.observePendingReimbursement().collect { refresh() }
            }
            // 账户余额会随收款/改账户变化，跟着观察而不是拉一次快照
            viewModelScope.launch {
                accountRepository.observeWithBalances().collect { list ->
                    _state.update { it.copy(fundingAccounts = FundPayable.payableAccounts(list)) }
                }
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

        /**
         * 确认报销（v1.9.4 第 2 条：多了到账账户与报销时间）。
         *
         * @param accountId 到账账户；null 时沿用原支出的付款账户
         * @param dateTime 报销收入的发生时间（用户选的日期 + 时刻）
         */
        fun confirm(
            txnId: String,
            amountText: String,
            accountId: String? = null,
            dateTime: Long = System.currentTimeMillis(),
        ) {
            val cents = MoneyFormatter.yuanToCents(amountText)
            if (cents == null || cents <= 0) {
                _state.update { it.copy(message = "请输入有效的报销金额") }
                return
            }
            viewModelScope.launch {
                when (val result = reimburse.confirm(txnId, cents, accountId = accountId, dateTime = dateTime)) {
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

/**
 * 拆分待报销列表（v1.9.4 第 5 条）。
 *
 * 只给待报销分组——已报销最多 50 条且是「查历史」场景，平铺更好扫。
 *
 * 三条约束：
 * - 组顺序按 [Txn.dateKey] 倒序（最近的在最上），不依赖仓库查询的 `ORDER BY`：
 *   那个查询同时被首页胶囊共用，一旦有人为了别的页面改排序，分组顺序会静默变化；
 * - 组内按 `dateTime` 倒序，同一时刻再按 `createdAt` 倒序，保证稳定；
 * - 分组键用整型 `dateKey` 而不是「11月8日」这种文案——文案明年还会再来一次。
 */
fun pendingGroupsOf(rows: List<ReimbursementRow>): List<PendingGroup> =
    rows
        .groupBy { it.txn.dateKey }
        .toSortedMap(compareByDescending { it })
        .map { (dateKey, group) ->
            val sorted =
                group.sortedWith(
                    compareByDescending<ReimbursementRow> { it.txn.dateTime }
                        .thenByDescending { it.txn.createdAt },
                )
            PendingGroup(
                dateKey = dateKey,
                dateLabel = sorted.first().dateLabel,
                rows = sorted,
                total = sorted.sumOf { it.txn.amount },
            )
        }

/** 供首页胶囊使用：待报销笔数与合计。 */
suspend fun pendingReimbursementOf(repository: TxnRepository): Pair<Int, Long> {
    val list = repository.observePendingReimbursement().first()
    return list.size to list.sumOf { it.amount }
}
