package com.xxjz.ledger.feature.ledger

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xxjz.ledger.core.common.AppResult
import com.xxjz.ledger.core.common.DateUtils
import com.xxjz.ledger.domain.model.AccountType
import com.xxjz.ledger.domain.model.AppSettings
import com.xxjz.ledger.domain.model.Ids
import com.xxjz.ledger.domain.model.Installment
import com.xxjz.ledger.domain.model.Template
import com.xxjz.ledger.domain.model.Txn
import com.xxjz.ledger.domain.model.TxnType
import com.xxjz.ledger.domain.model.shouldShowBackupHint
import com.xxjz.ledger.domain.repository.AccountRepository
import com.xxjz.ledger.domain.repository.CategoryRepository
import com.xxjz.ledger.domain.repository.InstallmentRepository
import com.xxjz.ledger.domain.repository.SettingsRepository
import com.xxjz.ledger.domain.repository.TemplateRepository
import com.xxjz.ledger.domain.repository.TxnRepository
import com.xxjz.ledger.domain.usecase.StartInstallmentUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.YearMonth
import javax.inject.Inject

/** 明细首页状态。 */
data class LedgerUiState(
    val monthKey: Int = 0,
    val monthLabel: String = "",
    val income: Long = 0,
    val expense: Long = 0,
    val groups: List<DayGroup> = emptyList(),
    /** 本月待报销金额合计（分），只算未删除的支出（v1.9.5 第 5 条）。 */
    val pendingTotal: Long = 0,
    /** 本月待报销笔数。 */
    val pendingCount: Int = 0,
    val templates: List<Template> = emptyList(),
    val loading: Boolean = true,
    /** 距上次备份超过 7 天且没被关掉时的提示文案（v1.9.6 第 1 条）。 */
    val backupHint: String? = null,
    /** 底部预算概览卡（v1.8.0 / PRD F4）。 */
    val budget: BudgetCardState = BudgetCardState(),
) {
    val balance: Long get() = income - expense
    val count: Int get() = groups.sumOf { it.rows.size }

    /**
     * 首页只显示最近几笔（v1.9.6 第 2 条）。
     *
     * 以前整月全量铺开，一个月几十笔时「这个月大概什么样」被淹没在长列表里，
     * 而且要滚很久才够得到预算卡。现在首页当概览用，完整清单交给「更多明细」。
     */
    val recentRows: List<TxnRow> get() = groups.asSequence().flatMap { it.rows }.take(RECENT_LIMIT).toList()

    /** 是否还有没显示出来的流水（决定要不要出「更多明细」卡）。 */
    val hasMore: Boolean get() = count > RECENT_LIMIT

    /** 本月已过天数与日均支出（用于小结条）。 */
    val elapsedDays: Int get() = DateUtils.elapsedDaysInMonth()
    val dailyAverage: Long get() = if (elapsedDays <= 0) 0 else expense / elapsedDays

    companion object {
        /** 首页显示的流水条数上限。 */
        const val RECENT_LIMIT = 4
    }
}

/** 明细首页 ViewModel：按月订阅流水 + 分类，组装分组与月小计（AC-26）。 */
@HiltViewModel
class LedgerViewModel
    @Inject
    constructor(
        private val txnRepository: TxnRepository,
        private val categoryRepository: CategoryRepository,
        private val templateRepository: TemplateRepository,
        private val budgetForMonth: com.xxjz.ledger.domain.usecase.GetBudgetForMonthUseCase,
        private val accountRepository: AccountRepository,
        private val installmentRepository: InstallmentRepository,
        private val startInstallmentUseCase: StartInstallmentUseCase,
        private val settingsRepository: SettingsRepository,
    ) : ViewModel() {
        private val month = MutableStateFlow(YearMonth.now())

        private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
        val messages: SharedFlow<String> = _messages.asSharedFlow()

        @OptIn(ExperimentalCoroutinesApi::class)
        val state: StateFlow<LedgerUiState> =
            month
                .flatMapLatest { ym ->
                    val range = DateUtils.monthRange(ym.year, ym.monthValue)
                    // 六个流超出 combine 的泛型重载上限，先并三股再并四股；
                    // 账户是行内「谁付的钱」的来源，必须一起观察，否则改名后列表不刷新
                    val core =
                        combine(
                            txnRepository.observeInRange(range.first, range.last),
                            categoryRepository.observeAll(),
                            accountRepository.observeWithBalances(),
                        ) { txns, categories, accounts -> Triple(txns, categories, accounts) }
                    // 备份提示要在首页显示，settings 流一并参与组装（v1.9.6 第 1 条）
                    combine(
                        core,
                        settingsRepository.settings,
                        templateRepository.observeAll(),
                        budgetForMonth.observe(DateUtils.monthKeyOf(ym.atDay(1))),
                    ) { (txns, categories, accounts), pref, templates, budgets ->
                        val map = categories.associateBy { it.id }
                        val groups = LedgerGrouper.group(txns, map, accounts = accounts.associateBy { it.id })
                        val counted = txns.filter { !it.excludedFromStats }
                        // 待报销与「是否计入收支」无关：被排除统计的那笔照样能报销。
                        // 但转账与借贷不是「花出去的钱」，没有报销这回事。
                        val pending =
                            txns.filter {
                                it.pendingReimbursement && !it.isDeleted && it.type == TxnType.EXPENSE
                            }
                        val expenseSum = counted.filter { it.type == com.xxjz.ledger.domain.model.TxnType.EXPENSE }.sumOf { it.amount }
                        LedgerUiState(
                            monthKey = DateUtils.monthKeyOf(ym.atDay(1)),
                            monthLabel = "${ym.year}年${ym.monthValue}月",
                            income = counted.filter { it.type == com.xxjz.ledger.domain.model.TxnType.INCOME }.sumOf { it.amount },
                            expense = expenseSum,
                            groups = groups,
                            pendingTotal = pending.sumOf { it.amount },
                            pendingCount = pending.size,
                            templates = templates,
                            backupHint = backupHintOf(pref),
                            budget =
                                BudgetCardState.from(
                                    budgets,
                                    measuredExpense = expenseSum,
                                ),
                            loading = false,
                        )
                    }
                }.stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(5_000),
                    initialValue =
                        LedgerUiState(
                            monthKey = DateUtils.monthKeyOf(month.value.atDay(1)),
                            monthLabel = "${month.value.year}年${month.value.monthValue}月",
                        ),
                )

        fun selectMonth(
            year: Int,
            monthValue: Int,
        ) {
            month.value = YearMonth.of(year, monthValue)
        }

        /**
         * 首页备份提示文案（v1.9.6 第 1 条）。
         *
         * 两句文案语气不同：从没备份过要告诉用户去哪，「隔太久」只是提醒该做了。
         * 什么时候不显示由 [shouldShowBackupHint] 统一判定，这里只管措辞。
         */
        private fun backupHintOf(pref: AppSettings): String? {
            val show =
                shouldShowBackupHint(
                    reminderEnabled = pref.backupReminderEnabled,
                    lastBackupAt = pref.lastBackupAt,
                    dismissedAt = pref.backupHintDismissedAt,
                    now = System.currentTimeMillis(),
                )
            if (!show) return null
            return if (pref.lastBackupAt <= 0) "还没有备份过，建议导出一份" else "距上次备份已超过 7 天"
        }

        /** 点掉首页的备份提示：只静默 7 天，不碰设置页的「备份提醒」总开关。 */
        fun dismissBackupHint() {
            viewModelScope.launch { settingsRepository.dismissBackupHint() }
        }

        fun previousMonth() {
            month.value = month.value.minusMonths(1)
        }

        fun nextMonth() {
            month.value = month.value.plusMonths(1)
        }

        fun delete(id: String) {
            viewModelScope.launch {
                txnRepository.softDelete(id, System.currentTimeMillis())
                _messages.emit("已移入回收站")
            }
        }

        /** 长按菜单「报销」：在「待报销」与普通账目之间来回切（PRD v1.9.0 第 3 条）。 */
        fun toggleReimbursement(txn: Txn) {
            viewModelScope.launch {
                val next = !txn.pendingReimbursement
                txnRepository.update(
                    txn.copy(pendingReimbursement = next, updatedAt = System.currentTimeMillis()),
                )
                _messages.emit(if (next) "已标记为待报销" else "已取消待报销")
            }
        }

        // ---------- 分期（v1.9.5 第 9 条） ----------

        /** 只有「支出 + 信用账户」才给分期入口，其余类型连菜单项都不出现。 */
        suspend fun canInstallment(txn: Txn): Boolean =
            txn.type == TxnType.EXPENSE &&
                txn.accountId?.let { accountRepository.byId(it)?.type } == AccountType.CREDIT

        /** 这笔交易是否已有分期计划；有的话弹层切只读。 */
        suspend fun installmentOf(txnId: String): Installment? =
            installmentRepository.observeAll().first().firstOrNull { it.txnId == txnId }

        /**
         * 对**本笔**发起分期。
         *
         * 用例会把原流水改成「本期应还」，剩下的期数交给每月自动出账引擎；
         * 以后回列表就能看到金额变小、每月多出一笔。
         */
        fun startInstallment(
            txn: Txn,
            periods: Int,
            paidPeriods: Int,
        ) {
            viewModelScope.launch {
                when (val result = startInstallmentUseCase(txn.id, periods, paidPeriods)) {
                    is AppResult.Success -> _messages.emit("已分 $periods 期，每月自动出账")
                    is AppResult.Failure -> _messages.emit(result.error.message)
                }
            }
        }

        /** 长按菜单「更多 → 创建模板记账」：把当前这笔存成模板，下次一键复用。 */
        fun saveAsTemplate(
            txn: Txn,
            name: String,
        ) {
            viewModelScope.launch {
                templateRepository.upsert(
                    Template(
                        id = Ids.newId("tpl"),
                        name = name.ifBlank { "记账模板" }.take(12),
                        type = txn.type,
                        amount = txn.amount,
                        categoryId = txn.categoryId,
                        accountId = txn.accountId,
                        toAccountId = txn.toAccountId,
                        note = txn.note,
                    ),
                )
                _messages.emit("已保存为模板「$name」")
            }
        }

        /** 批量模式：一次删掉选中的多条。 */
        fun batchDelete(ids: Collection<String>) {
            if (ids.isEmpty()) return
            viewModelScope.launch {
                val now = System.currentTimeMillis()
                ids.forEach { txnRepository.softDelete(it, now) }
                _messages.emit("已把 ${ids.size} 笔移入回收站")
            }
        }

        /** 批量模式：一次把选中的多条标记为待报销。 */
        fun batchReimburse(txns: List<Txn>) {
            if (txns.isEmpty()) return
            viewModelScope.launch {
                val now = System.currentTimeMillis()
                txns.forEach { txnRepository.update(it.copy(pendingReimbursement = true, updatedAt = now)) }
                _messages.emit("已标记 ${txns.size} 笔为待报销")
            }
        }
    }
