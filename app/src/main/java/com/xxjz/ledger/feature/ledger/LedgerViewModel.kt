package com.xxjz.ledger.feature.ledger

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xxjz.ledger.core.common.DateUtils
import com.xxjz.ledger.data.local.seed.SeedData
import com.xxjz.ledger.domain.model.Template
import com.xxjz.ledger.domain.model.Txn
import com.xxjz.ledger.domain.model.TxnType
import com.xxjz.ledger.domain.repository.AccountRepository
import com.xxjz.ledger.domain.repository.CategoryRepository
import com.xxjz.ledger.domain.repository.TemplateRepository
import com.xxjz.ledger.domain.repository.TxnRepository
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
    /** 底部预算概览卡（v1.8.0 / PRD F4）。 */
    val budget: BudgetCardState = BudgetCardState(),
) {
    val balance: Long get() = income - expense
    val count: Int get() = groups.sumOf { it.rows.size }

    /** 本月已过天数与日均支出（用于小结条）。 */
    val elapsedDays: Int get() = DateUtils.elapsedDaysInMonth()
    val dailyAverage: Long get() = if (elapsedDays <= 0) 0 else expense / elapsedDays
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
                    // 备份提示已从首页撤掉（v1.9.5 第 7 条），settings 流不再参与组装
                    combine(
                        core,
                        templateRepository.observeAll(),
                        budgetForMonth.observe(DateUtils.monthKeyOf(ym.atDay(1))),
                    ) { (txns, categories, accounts), templates, budgets ->
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

        /** 长按菜单「更多 → 创建模板记账」：把当前这笔存成模板，下次一键复用。 */
        fun saveAsTemplate(
            txn: Txn,
            name: String,
        ) {
            viewModelScope.launch {
                templateRepository.upsert(
                    Template(
                        id = SeedData.newId("tpl"),
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
