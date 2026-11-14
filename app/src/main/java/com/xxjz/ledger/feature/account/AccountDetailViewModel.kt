package com.xxjz.ledger.feature.account

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xxjz.ledger.core.common.DateUtils
import com.xxjz.ledger.domain.model.Account
import com.xxjz.ledger.domain.model.Txn
import com.xxjz.ledger.domain.model.TxnType
import com.xxjz.ledger.domain.repository.AccountRepository
import com.xxjz.ledger.domain.repository.CategoryRepository
import com.xxjz.ledger.domain.repository.TxnRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 账户详情中的一行流水。 */
data class AccountTxnRow(
    val txn: Txn,
    val categoryName: String,
)

/** P14 账户详情状态。 */
data class AccountDetailUiState(
    val account: Account? = null,
    val monthIncome: Long = 0,
    val monthExpense: Long = 0,
    val totalCount: Int = 0,
    val rows: List<AccountTxnRow> = emptyList(),
    val loading: Boolean = true,
) {
    /** 信用卡可用额度 = 额度 + 余额（余额为负表示欠款）。 */
    val availableLimit: Long? get() = account?.cardLimit?.let { it + account.balance }
}

/** 账户详情（AC-18 / AC-24）。 */
@HiltViewModel
class AccountDetailViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val accountRepository: AccountRepository,
        txnRepository: TxnRepository,
        categoryRepository: CategoryRepository,
    ) : ViewModel() {
        private val accountId: String = savedStateHandle.get<String>("accountId").orEmpty()

        val state: StateFlow<AccountDetailUiState> =
            combine(
                accountRepository.observeWithBalances(),
                txnRepository.observeByAccount(accountId),
                categoryRepository.observeAll(),
            ) { accounts, txns, categories ->
                val account = accounts.firstOrNull { it.id == accountId }
                val map = categories.associateBy { it.id }
                val monthKey = DateUtils.monthKeyOf(DateUtils.today())
                val monthTxns = txns.filter { it.dateKey / 100 == monthKey }
                AccountDetailUiState(
                    account = account,
                    monthIncome = monthTxns.filter { it.type == TxnType.INCOME }.sumOf { it.amount },
                    monthExpense = monthTxns.filter { it.type == TxnType.EXPENSE }.sumOf { it.amount },
                    totalCount = txns.size,
                    rows =
                        txns.take(50).map { txn ->
                            AccountTxnRow(
                                txn = txn,
                                categoryName =
                                    txn.categoryId?.let { map[it]?.name }
                                        ?: if (txn.type == TxnType.TRANSFER) "转账" else "未分类",
                            )
                        },
                    loading = false,
                )
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = AccountDetailUiState(),
            )

        /** 改市值（v1.8.0 / PRD F2）：不改流水、不生成记账，只更新市值并留一条资产变化记录。 */
        fun updateMarketValue(cents: Long) {
            viewModelScope.launch {
                accountRepository.updateMarketValue(accountId, cents, System.currentTimeMillis())
            }
        }

        /** 是否把该投资账户市值计入净资产（PRD D2）。 */
        fun setIncludeInNetAssets(include: Boolean) {
            viewModelScope.launch {
                val account = accountRepository.byId(accountId) ?: return@launch
                accountRepository.upsert(account.copy(includeInNetAssets = include))
            }
        }
    }
