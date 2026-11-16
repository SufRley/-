package com.xxjz.ledger.feature.trash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xxjz.ledger.core.common.MoneyFormatter
import com.xxjz.ledger.domain.model.Account
import com.xxjz.ledger.domain.model.Budget
import com.xxjz.ledger.domain.model.Txn
import com.xxjz.ledger.domain.model.TxnType
import com.xxjz.ledger.domain.repository.AccountRepository
import com.xxjz.ledger.domain.repository.BudgetRepository
import com.xxjz.ledger.domain.repository.SettingsRepository
import com.xxjz.ledger.domain.repository.TxnRepository
import com.xxjz.ledger.domain.usecase.TrashCleanupUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 回收站三个分页。 */
enum class TrashTab(
    val label: String,
) {
    TXN("账目"),
    ACCOUNT("账户"),
    BUDGET("预算"),
}

/** 回收站里的一条记录。 */
data class TrashItem(
    val id: String,
    val title: String,
    val subtitle: String,
    /** 预算需要 (id, monthKey) 才能定位。 */
    val monthKey: Int = 0,
)

data class TrashState(
    val tab: TrashTab = TrashTab.TXN,
    val txns: List<TrashItem> = emptyList(),
    val accounts: List<TrashItem> = emptyList(),
    val budgets: List<TrashItem> = emptyList(),
    /** 保留天数；0 表示永久保留。 */
    val retentionDays: Int = 30,
    val message: String? = null,
) {
    val current: List<TrashItem>
        get() =
            when (tab) {
                TrashTab.TXN -> txns
                TrashTab.ACCOUNT -> accounts
                TrashTab.BUDGET -> budgets
            }

    val total: Int get() = txns.size + accounts.size + budgets.size

    /** 文案里要能看出「多少天后自动清理」。 */
    val retentionLabel: String
        get() = if (retentionDays <= 0) "永久保留，不会自动清理" else "删除后保留 $retentionDays 天，到期自动清理"
}

/** P22 回收站（AC-34、AC-50、AC-76）。 */
@HiltViewModel
class TrashViewModel
    @Inject
    constructor(
        private val txnRepository: TxnRepository,
        private val accountRepository: AccountRepository,
        private val budgetRepository: BudgetRepository,
        private val settings: SettingsRepository,
        private val cleanup: TrashCleanupUseCase,
    ) : ViewModel() {
        private val _state = MutableStateFlow(TrashState())
        val state: StateFlow<TrashState> = _state.asStateFlow()

        init {
            // 上游汇总放在后台线程：这里只做「把几个 Flow 折叠成 StateFlow」，不碰 UI。
            // 放 Dispatchers.Default 而不是默认的 Main，可以避免主线程被仓库的持续发射占住，
            // 数据一变状态就跟着变，不必等主线程空闲。
            viewModelScope.launch(Dispatchers.Default) {
                combine(
                    txnRepository.observeDeleted(),
                    accountRepository.observeDeleted(),
                    budgetRepository.observeDeleted(),
                    settings.settings,
                ) { txns, accounts, budgets, s ->
                    TrashState(
                        tab = _state.value.tab,
                        txns = txns.map { it.toItem() },
                        accounts = accounts.map { it.toItem() },
                        budgets = budgets.map { it.toItem() },
                        retentionDays = s.recycleBinDays,
                        message = _state.value.message,
                    )
                }.collect { next ->
                    _state.value = next
                }
            }
        }

        fun selectTab(tab: TrashTab) = _state.update { it.copy(tab = tab) }

        fun consumeMessage() = _state.update { it.copy(message = null) }

        /** 还原一条账目（AC-34）。 */
        fun restoreTxn(id: String) =
            viewModelScope.launch {
                txnRepository.restore(id, System.currentTimeMillis())
                _state.update { it.copy(message = "已还原账目") }
            }

        fun purgeTxn(id: String) =
            viewModelScope.launch {
                txnRepository.hardDelete(id)
                _state.update { it.copy(message = "已彻底删除") }
            }

        fun restoreAccount(id: String) =
            viewModelScope.launch {
                accountRepository.restore(id, System.currentTimeMillis())
                _state.update { it.copy(message = "已还原账户及其账目") }
            }

        fun purgeAccount(id: String) =
            viewModelScope.launch {
                accountRepository.purge(id)
                _state.update { it.copy(message = "已彻底删除账户") }
            }

        fun restoreBudget(
            id: String,
            monthKey: Int,
        ) = viewModelScope.launch {
            budgetRepository.restore(id, monthKey)
            _state.update { it.copy(message = "已还原预算") }
        }

        fun purgeBudget(
            id: String,
            monthKey: Int,
        ) = viewModelScope.launch {
            budgetRepository.delete(id, monthKey)
            _state.update { it.copy(message = "已彻底删除预算") }
        }

        /** 清空当前分页。逐条硬删，条数有限（回收站不会堆太多）。 */
        fun clearCurrent() =
            viewModelScope.launch {
                val snapshot = _state.value
                val count = snapshot.current.size
                when (snapshot.tab) {
                    TrashTab.TXN -> snapshot.txns.forEach { txnRepository.hardDelete(it.id) }
                    TrashTab.ACCOUNT -> snapshot.accounts.forEach { accountRepository.purge(it.id) }
                    TrashTab.BUDGET -> snapshot.budgets.forEach { budgetRepository.delete(it.id, it.monthKey) }
                }
                _state.update { it.copy(message = "已清空 $count 条") }
            }

        /** 立即执行一次到期清理，便于用户在设置里手动触发。 */
        fun cleanupNow() =
            viewModelScope.launch {
                val result = cleanup()
                val count = (result as? com.xxjz.ledger.core.common.AppResult.Success)?.data ?: 0
                _state.update { it.copy(message = if (count == 0) "没有需要清理的记录" else "已清理 $count 条") }
            }

        private fun Txn.toItem(): TrashItem {
            val label =
                when (type) {
                    TxnType.EXPENSE -> "支出"
                    TxnType.INCOME -> "收入"
                    TxnType.TRANSFER -> "转账"
                    TxnType.DEBT -> "借贷"
                }
            val title = if (note.isNotBlank()) note else label
            val subtitle = "$label ${MoneyFormatter.centsToYuan(amount, showSymbol = true, symbol = "¥")} · $dateKey"
            return TrashItem(id = id, title = title, subtitle = subtitle)
        }

        private fun Account.toItem(): TrashItem = TrashItem(id = id, title = name, subtitle = "账户")

        private fun Budget.toItem(): TrashItem =
            TrashItem(
                id = id,
                title = name,
                subtitle = "${monthKey / 100} 年 ${monthKey % 100} 月 · ${MoneyFormatter.centsToYuan(amount, showSymbol = true)}",
                monthKey = monthKey,
            )
    }
