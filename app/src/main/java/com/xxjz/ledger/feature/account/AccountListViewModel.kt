package com.xxjz.ledger.feature.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xxjz.ledger.core.common.AppResult
import com.xxjz.ledger.data.datastore.SettingsRepository
import com.xxjz.ledger.domain.model.Account
import com.xxjz.ledger.domain.model.AccountType
import com.xxjz.ledger.domain.repository.AccountRepository
import com.xxjz.ledger.domain.repository.DebtRepository
import com.xxjz.ledger.domain.repository.TxnRepository
import com.xxjz.ledger.domain.usecase.AssetSummary
import com.xxjz.ledger.domain.usecase.DeleteAccountUseCase
import com.xxjz.ledger.domain.usecase.GetAssetSummaryUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 账户分组。 */
data class AccountGroup(
    val type: AccountType,
    val title: String,
    /** 组内合计：投资组按市值，其余按余额。 */
    val total: Long,
    val accounts: List<Account>,
    val collapsed: Boolean,
)

/** 入口卡数据（待报销 / 借贷台账）。 */
data class EntryCardState(
    val count: Int = 0,
    val amountCents: Long = 0,
)

/** P13 账户列表（资产中心）状态。 */
data class AccountListUiState(
    val summary: AssetSummary = AssetSummary(),
    val groups: List<AccountGroup> = emptyList(),
    val hidden: List<Account> = emptyList(),
    val showHidden: Boolean = false,
    val reimbursement: EntryCardState = EntryCardState(),
    val debts: EntryCardState = EntryCardState(),
    val loading: Boolean = true,
)

/**
 * 账户页（v1.8.0 / PRD 5.1）：五分组列表 + 净资产总览 + 待报销/借贷入口卡。
 *
 * 分组顺序固定为 现金 → 储蓄卡 → 信用 → 借贷 → 投资，空组不渲染；
 * 折叠状态放 DataStore，属于界面偏好，不进备份。
 */
@HiltViewModel
class AccountListViewModel
    @Inject
    constructor(
        private val repository: AccountRepository,
        private val txnRepository: TxnRepository,
        private val deleteAccount: DeleteAccountUseCase,
        private val summaryUseCase: GetAssetSummaryUseCase,
        private val settings: SettingsRepository,
        private val debtRepository: DebtRepository,
    ) : ViewModel() {
        private val showHidden = MutableStateFlow(false)
        private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
        val messages: SharedFlow<String> = _messages.asSharedFlow()

        val state: StateFlow<AccountListUiState> =
            combine(
                repository.observeWithBalances(),
                showHidden,
                settings.collapsedAccountGroups,
                txnRepository.observePendingReimbursement(),
                // 第 5 个来源：借贷台账的未结清净额，入口卡要显示「还欠多少」（PRD F3 / D5）
                debtRepository.observeOutstanding(),
            ) { accounts, hiddenVisible, collapsed, pending, outstanding ->
                val visible = accounts.filter { !it.hidden }
                val groups =
                    LABELS.mapNotNull { (type, title) ->
                        val inGroup = visible.filter { it.type == type }
                        if (inGroup.isEmpty()) {
                            null
                        } else {
                            AccountGroup(
                                type = type,
                                title = title,
                                total =
                                    inGroup.sumOf {
                                        if (it.type == AccountType.INVEST) it.marketValueCents else it.balance
                                    },
                                accounts = inGroup,
                                collapsed = type.name in collapsed,
                            )
                        }
                    }
                AccountListUiState(
                    summary = summaryUseCase(accounts),
                    groups = groups,
                    hidden = accounts.filter { it.hidden },
                    showHidden = hiddenVisible,
                    reimbursement = EntryCardState(pending.size, pending.sumOf { it.amount - it.reimbursedAmount }),
                    debts = EntryCardState(outstanding.count { it.second > 0 }, outstanding.sumOf { it.second }),
                    loading = false,
                )
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = AccountListUiState(),
            )

        fun toggleShowHidden() {
            showHidden.value = !showHidden.value
        }

        /** 折叠/展开一个分组，写回 DataStore。 */
        fun toggleGroup(type: AccountType) {
            viewModelScope.launch {
                val current = settings.collapsedAccountGroups.first()
                val next = if (type.name in current) current - type.name else current + type.name
                settings.setCollapsedAccountGroups(next)
            }
        }

        fun save(account: Account) {
            viewModelScope.launch {
                repository.upsert(account)
                _messages.emit("已保存")
            }
        }

        fun setHidden(
            id: String,
            hidden: Boolean,
        ) {
            viewModelScope.launch {
                repository.setHidden(id, hidden)
                _messages.emit(if (hidden) "已隐藏" else "已取消隐藏")
            }
        }

        fun delete(id: String) {
            viewModelScope.launch {
                when (val r = deleteAccount(id)) {
                    is AppResult.Success -> _messages.emit("已删除账户，${r.data} 笔账目移入回收站")
                    is AppResult.Failure -> _messages.emit(r.error.message)
                }
            }
        }

        companion object {
            /** 分组展示顺序与标题（PRD F3）。 */
            val LABELS: List<Pair<AccountType, String>> =
                listOf(
                    AccountType.CASH to "现金",
                    AccountType.SAVINGS to "储蓄卡",
                    AccountType.CREDIT to "信用账户",
                    AccountType.DEBT to "借贷账户",
                    AccountType.INVEST to "投资",
                )
        }
    }
