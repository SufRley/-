package com.xxjz.ledger.feature.account

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xxjz.ledger.core.common.AppResult
import com.xxjz.ledger.core.common.DateUtils
import com.xxjz.ledger.core.common.MoneyFormatter
import com.xxjz.ledger.domain.model.Account
import com.xxjz.ledger.domain.model.AccountType
import com.xxjz.ledger.domain.model.DebtAction
import com.xxjz.ledger.domain.model.FeeMode
import com.xxjz.ledger.domain.model.InstallmentScope
import com.xxjz.ledger.domain.model.Txn
import com.xxjz.ledger.domain.model.TxnType
import com.xxjz.ledger.domain.repository.AccountRepository
import com.xxjz.ledger.domain.repository.CategoryRepository
import com.xxjz.ledger.domain.repository.TxnRepository
import com.xxjz.ledger.domain.usecase.CreateInstallmentUseCase
import com.xxjz.ledger.domain.usecase.CreditBill
import com.xxjz.ledger.domain.usecase.DebtOpDraft
import com.xxjz.ledger.domain.usecase.GetCreditBillUseCase
import com.xxjz.ledger.domain.usecase.InstallmentDraft
import com.xxjz.ledger.domain.usecase.MergeDebtAccountUseCase
import com.xxjz.ledger.domain.usecase.RecordDebtOpUseCase
import com.xxjz.ledger.domain.usecase.RepayCreditUseCase
import com.xxjz.ledger.domain.usecase.WriteOffDebtUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 账户详情中的一行流水。 */
data class AccountTxnRow(
    val txn: Txn,
    val categoryName: String,
    /** 已分期时显示「已分 N 期」标签（v1.9.0 / PRD 9.9）。 */
    val installmentPeriods: Int? = null,
)

/** P14 账户详情状态。 */
data class AccountDetailUiState(
    val account: Account? = null,
    val monthIncome: Long = 0,
    val monthExpense: Long = 0,
    val totalCount: Int = 0,
    val rows: List<AccountTxnRow> = emptyList(),
    /** 借贷操作可选的资金账户（v1.9.0）：借贷账户不能当资金账户。 */
    val fundAccounts: List<Account> = emptyList(),
    /** 可合并的目标借贷账户（v1.9.0 / PRD 3.4）：同类型的其他借贷账户。 */
    val mergeTargets: List<Account> = emptyList(),
    /** 信用账户本期账单（v1.9.0 / PRD 3.4），非信用账户为 null。 */
    val bill: CreditBill? = null,
    /** 可选的单笔分期对象：本期未分期的消费。 */
    val installmentCandidates: List<Txn> = emptyList(),
    val loading: Boolean = true,
) {
    val isCredit: Boolean get() = account?.type == AccountType.CREDIT

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
        private val recordDebtOp: RecordDebtOpUseCase,
        private val writeOffDebt: WriteOffDebtUseCase,
        private val getCreditBill: GetCreditBillUseCase,
        private val createInstallment: CreateInstallmentUseCase,
        private val repayCredit: RepayCreditUseCase,
        private val mergeDebtAccount: MergeDebtAccountUseCase,
    ) : ViewModel() {
        private val _message = MutableSharedFlow<String>(extraBufferCapacity = 4)

        /** 借贷操作结果提示（成功 / 失败原因）。 */
        val message: SharedFlow<String> = _message.asSharedFlow()

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
                val bill = if (account?.type == AccountType.CREDIT) getCreditBill.compute(account) else null
                // 可分期对象：本期未分期的消费
                val splitPeriods =
                    bill?.activePlans.orEmpty()
                        .mapNotNull { p -> p.plan.txnId?.let { it to p.periods } }
                        .toMap()
                val alreadySplit = splitPeriods.keys
                val candidates =
                    if (bill == null) {
                        emptyList()
                    } else {
                        txns.filter {
                            it.type == TxnType.EXPENSE && !it.isDeleted &&
                                it.dateTime in bill.cycle.startAt..bill.cycle.endAt && it.id !in alreadySplit
                        }
                    }
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
                                installmentPeriods = splitPeriods[txn.id],
                            )
                        },
                    fundAccounts = accounts.filter { it.type != AccountType.DEBT && it.type != AccountType.CREDIT },
                    mergeTargets =
                        if (account?.type == AccountType.DEBT) {
                            accounts.filter { it.type == AccountType.DEBT && it.id != accountId }
                        } else {
                            emptyList()
                        },
                    bill = bill,
                    installmentCandidates = candidates,
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

        /**
         * 借贷操作（v1.9.0 / PRD 2.5）：收款 / 还款。
         *
         * 金额与资金账户都由弹窗传入，成功后流水立刻反映到余额（余额是聚合出来的，不落库）。
         */
        fun recordDebt(
            action: DebtAction,
            amountCents: Long,
            fundAccountId: String,
        ) {
            viewModelScope.launch {
                val result =
                    recordDebtOp(
                        DebtOpDraft(
                            action = action,
                            debtAccountId = accountId,
                            fundAccountId = fundAccountId,
                            amountCents = amountCents,
                        ),
                    )
                _message.emit(
                    when (result) {
                        is AppResult.Success -> "已记${RecordDebtOpUseCase.ACTION_LABELS[action].orEmpty()}"
                        is AppResult.Failure -> result.error.message
                    },
                )
            }
        }

        /** 坏账核销：把待收余额清零，净资产随之减少（唯一会改变净资产的借贷操作）。 */
        fun writeOff() {
            viewModelScope.launch {
                val result = writeOffDebt(accountId)
                _message.emit(
                    when (result) {
                        is AppResult.Success -> "已核销坏账"
                        is AppResult.Failure -> result.error.message
                    },
                )
            }
        }

        /**
         * 合并借贷账户（v1.9.0 / PRD 3.4）：把当前账户的流水并到 [targetId]，当前账户进回收站。
         *
         * 合并后余额为两边净值之和，符号自然体现谁欠谁。
         */
        fun mergeInto(targetId: String) {
            viewModelScope.launch {
                val result = mergeDebtAccount(accountId, targetId)
                _message.emit(
                    when (result) {
                        is AppResult.Success -> "已合并，合并后余额 ¥${MoneyFormatter.centsToYuan(result.data)}"
                        is AppResult.Failure -> result.error.message
                    },
                )
            }
        }

        /**
         * 建分期（PRD 3.5）。只写分期表、不产生流水，因此净资产不变。
         *
         * @param txnId 非空表示「选一笔消费分期」，为空表示「本期账单分期」
         */
        fun createInstallment(
            txnId: String?,
            principalCents: Long,
            periods: Int,
            feeCents: Long,
            feeMode: FeeMode,
        ) {
            viewModelScope.launch {
                val bill = state.value.bill ?: return@launch
                val result =
                    createInstallment(
                        InstallmentDraft(
                            scope = if (txnId == null) InstallmentScope.BILL else InstallmentScope.TXN,
                            txnId = txnId,
                            accountId = accountId,
                            principalCents = principalCents,
                            feeCents = feeCents,
                            feeMode = feeMode,
                            periods = periods,
                            startBillKey = bill.cycle.billKey,
                        ),
                    )
                _message.emit(
                    when (result) {
                        is AppResult.Success -> "已分 $periods 期"
                        is AppResult.Failure -> result.error.message
                    },
                )
            }
        }

        /** 还本期账单（PRD 3.5）：生成一笔信用账户支出，资金账户同步扣减。 */
        fun repayBill(
            amountCents: Long,
            fundAccountId: String,
        ) {
            viewModelScope.launch {
                val result = repayCredit(accountId, fundAccountId, amountCents)
                _message.emit(
                    when (result) {
                        is AppResult.Success -> "已还款"
                        is AppResult.Failure -> result.error.message
                    },
                )
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
