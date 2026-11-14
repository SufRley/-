package com.xxjz.ledger.feature.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xxjz.ledger.core.common.AppResult
import com.xxjz.ledger.domain.model.Account
import com.xxjz.ledger.domain.model.AccountType
import com.xxjz.ledger.domain.repository.AccountRepository
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 账户分组。 */
data class AccountGroup(
    val type: AccountType,
    val title: String,
    val accounts: List<Account>,
)

/** P13 账户列表状态。 */
data class AccountListUiState(
    val summary: AssetSummary = AssetSummary(),
    val groups: List<AccountGroup> = emptyList(),
    val hidden: List<Account> = emptyList(),
    val showHidden: Boolean = false,
    val loading: Boolean = true,
)

/** 账户列表（AC-16 / AC-20 / AC-21）：三组展示、资产汇总、隐藏与删除。 */
@HiltViewModel
class AccountListViewModel
    @Inject
    constructor(
        private val repository: AccountRepository,
        private val deleteAccount: DeleteAccountUseCase,
        private val summaryUseCase: GetAssetSummaryUseCase,
    ) : ViewModel() {
        private val showHidden = MutableStateFlow(false)
        private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
        val messages: SharedFlow<String> = _messages.asSharedFlow()

        val state: StateFlow<AccountListUiState> =
            combine(repository.observeWithBalances(), showHidden) { accounts, hiddenVisible ->
                val visible = accounts.filter { !it.hidden }
                val groups =
                    listOf(
                        AccountType.CASH to "现金",
                        AccountType.SAVINGS to "储蓄卡",
                        AccountType.CREDIT to "信用账户",
                        AccountType.DEBT to "借贷账户",
                        AccountType.INVEST to "投资",
                    ).mapNotNull { (type, title) ->
                        val inGroup = visible.filter { it.type == type }
                        if (inGroup.isEmpty()) null else AccountGroup(type, title, inGroup)
                    }
                AccountListUiState(
                    summary = summaryUseCase(accounts),
                    groups = groups,
                    hidden = accounts.filter { it.hidden },
                    showHidden = hiddenVisible,
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
    }
