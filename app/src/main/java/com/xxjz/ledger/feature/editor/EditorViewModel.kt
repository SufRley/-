package com.xxjz.ledger.feature.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xxjz.ledger.core.common.AppResult
import com.xxjz.ledger.core.common.DateUtils
import com.xxjz.ledger.core.common.MoneyFormatter
import com.xxjz.ledger.data.datastore.DefaultTimeMode
import com.xxjz.ledger.data.datastore.SettingsRepository
import com.xxjz.ledger.domain.model.Account
import com.xxjz.ledger.domain.model.Category
import com.xxjz.ledger.domain.model.TxnType
import com.xxjz.ledger.domain.repository.AccountRepository
import com.xxjz.ledger.domain.repository.CategoryRepository
import com.xxjz.ledger.domain.repository.TemplateRepository
import com.xxjz.ledger.domain.repository.TxnRepository
import com.xxjz.ledger.domain.usecase.SaveTxnUseCase
import com.xxjz.ledger.domain.usecase.TxnDraft
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalTime
import javax.inject.Inject

/** 记账面板一次性事件。 */
sealed interface EditorEvent {
    data class Saved(val id: String) : EditorEvent

    data class Message(val text: String) : EditorEvent
}

/**
 * 记账面板状态。刻意收敛成单一 data class，避免保存时读到不一致的中间态（DEV_PLAN M2 技术点）。
 */
data class EditorUiState(
    val editingId: String? = null,
    val type: TxnType = TxnType.EXPENSE,
    val calc: CalcState = CalcState(),
    val categoryId: String? = null,
    val accountId: String? = null,
    val toAccountId: String? = null,
    val dateTime: Long = System.currentTimeMillis(),
    val note: String = "",
    val feeText: String = "",
    val categories: List<Category> = emptyList(),
    val accounts: List<Account> = emptyList(),
    val saving: Boolean = false,
    val error: String? = null,
    /** 用户是否动过内容，用于误触关闭保护（AC-09）。 */
    val dirty: Boolean = false,
) {
    val amountCents: Long? get() = CalcEngine.centsOf(calc)

    val feeCents: Long get() = MoneyFormatter.yuanToCents(feeText) ?: 0L

    val categoryName: String? get() = categories.firstOrNull { it.id == categoryId }?.name

    val accountName: String? get() = accounts.firstOrNull { it.id == accountId }?.name

    val toAccountName: String? get() = accounts.firstOrNull { it.id == toAccountId }?.name

    /** 当前类型的完整分类树。 */
    val tree: List<Pair<Category, List<Category>>>
        get() {
            if (type != TxnType.EXPENSE && type != TxnType.INCOME) return emptyList()
            val ofType = categories.filter { it.type == type && !it.hidden }
            val children = ofType.filter { it.parentId != null }.groupBy { it.parentId }
            return ofType.filter { it.parentId == null }.map { major -> major to (children[major.id] ?: emptyList()) }
        }

    val amountDisplay: String
        get() = calc.display
}

/**
 * 记账面板 ViewModel：新建 / 编辑共用，靠 [loadForEdit] 区分。
 */
@HiltViewModel
class EditorViewModel
    @Inject
    constructor(
        private val txnRepository: TxnRepository,
        private val categoryRepository: CategoryRepository,
        private val accountRepository: AccountRepository,
        private val templateRepository: TemplateRepository,
        private val saveTxn: SaveTxnUseCase,
        private val settings: SettingsRepository,
    ) : ViewModel() {
        private val _state = MutableStateFlow(EditorUiState())
        val state: StateFlow<EditorUiState> = _state.asStateFlow()

        private val _events = MutableSharedFlow<EditorEvent>(extraBufferCapacity = 4)
        val events: SharedFlow<EditorEvent> = _events.asSharedFlow()

        init {
            observeCategories()
            observeAccounts()
            applyDefaultTime()
        }

        private fun observeCategories() {
            viewModelScope.launch {
                categoryRepository.observeAll().collect { list -> _state.update { it.copy(categories = list) } }
            }
        }

        private fun observeAccounts() {
            viewModelScope.launch {
                accountRepository.observeWithBalances().collect { list ->
                    _state.update { s ->
                        // 不预填第一个账户：以前 chip 直接显示「现金」，用户根本看不出那是个
                        // 账户选择器，于是「支出不能选账户」（v1.8.1 #4）。改成未选时显示「选账户」。
                        // 只有一个账户时无从选择，自动带上，免得每次多点一次。
                        s.copy(accounts = list, accountId = s.accountId ?: list.singleOrNull()?.id)
                    }
                }
            }
        }

        private fun applyDefaultTime() {
            viewModelScope.launch {
                val snapshot = runCatching { settings.current() }.getOrNull() ?: return@launch
                if (snapshot.defaultTimeMode != DefaultTimeMode.FIXED) return@launch
                val minutes = snapshot.fixedTimeMinutes
                val time = LocalTime.of(minutes / 60 % 24, minutes % 60)
                val millis = DateUtils.toMillis(DateUtils.today(), time)
                _state.update { it.copy(dateTime = millis) }
            }
        }

        /** 编辑模式预填。 */
        suspend fun loadForEdit(txnId: String) {
            val txn = txnRepository.byId(txnId) ?: return
            _state.update {
                it.copy(
                    editingId = txn.id,
                    type = txn.type,
                    calc = CalcState(buffer = MoneyFormatter.centsToPlain(txn.amount)),
                    categoryId = txn.categoryId,
                    accountId = txn.accountId,
                    toAccountId = txn.toAccountId,
                    dateTime = txn.dateTime,
                    note = txn.note,
                    feeText = if (txn.fee > 0) MoneyFormatter.centsToPlain(txn.fee) else "",
                    dirty = false,
                )
            }
        }

        /** 模板一键带入：类型/金额/备注回填，分类与账户为空时才用模板里的值。 */
        suspend fun loadTemplate(templateId: String) {
            val template = templateRepository.byId(templateId) ?: return
            _state.update {
                it.copy(
                    type = template.type,
                    calc = CalcState(buffer = MoneyFormatter.centsToPlain(template.amount)),
                    categoryId = it.categoryId ?: template.categoryId,
                    accountId = template.accountId ?: it.accountId,
                    toAccountId = template.toAccountId ?: it.toAccountId,
                    note = if (it.note.isBlank()) template.note else it.note,
                    dirty = true,
                )
            }
        }

        // ---------- 计算器 ----------

        fun digit(d: Char) = _state.update { it.copy(calc = CalcEngine.digit(it.calc, d), error = null, dirty = true) }

        fun dot() = _state.update { it.copy(calc = CalcEngine.dot(it.calc), error = null, dirty = true) }

        fun operator(op: CalcOp) = _state.update { it.copy(calc = CalcEngine.op(it.calc, op), error = null, dirty = true) }

        fun backspace() = _state.update { it.copy(calc = CalcEngine.backspace(it.calc), dirty = true) }

        fun clearAmount() = _state.update { it.copy(calc = CalcEngine.clear(), dirty = true) }

        /** `=`：第一次求值，第二次保存（AC-03）。 */
        fun equals() {
            val current = _state.value.calc
            if (current.evaluated) {
                submit()
            } else {
                _state.update { it.copy(calc = CalcEngine.evaluate(it.calc), dirty = true) }
            }
        }

        /**
         * 「保存」：先兜底求值，再提交（v1.8.1 #5）。
         *
         * 之前底部只有一个按钮，未求值时显示「=」，用户必须先按一次才能存——等于强制两步。
         * 现在保存常驻，表达式没算就先算。表达式不完整（以运算符结尾）由
         * [CalcEngine.evaluate] 报错，[submit] 拿到错误后不会写库。
         */
        fun submitOrEvaluate() {
            if (!_state.value.calc.evaluated) {
                _state.update { it.copy(calc = CalcEngine.evaluate(it.calc)) }
            }
            submit()
        }

        // ---------- 字段 ----------

        fun setType(type: TxnType) {
            if (type == _state.value.type) return
            _state.update {
                it.copy(
                    type = type,
                    categoryId = if (type == TxnType.EXPENSE || type == TxnType.INCOME) null else it.categoryId,
                    dirty = true,
                )
            }
        }

        fun selectCategory(id: String) = _state.update { it.copy(categoryId = id, dirty = true) }

        fun selectAccount(id: String) =
            _state.update {
                val to = if (it.type == TxnType.TRANSFER && it.toAccountId == id) it.accountId else it.toAccountId
                it.copy(accountId = id, toAccountId = to, dirty = true)
            }

        fun selectToAccount(id: String) =
            _state.update {
                val from = if (it.accountId == id) it.toAccountId else it.accountId
                it.copy(toAccountId = id, accountId = from, dirty = true)
            }

        fun setDateTime(millis: Long) = _state.update { it.copy(dateTime = millis, dirty = true) }

        fun setNote(text: String) = _state.update { it.copy(note = text.take(200), dirty = true) }

        fun setFee(text: String) {
            val filtered = text.filter { it.isDigit() || it == '.' }.take(12)
            _state.update { it.copy(feeText = filtered, dirty = true) }
        }

        fun dismissError() = _state.update { it.copy(error = null) }

        // ---------- 保存 ----------

        fun submit() {
            val s = _state.value
            if (s.saving) return
            val cents = s.amountCents
            if (cents == null) {
                val reason = s.calc.error ?: "请输入大于 0 的金额"
                _state.update { it.copy(error = reason) }
                return
            }
            val draft =
                TxnDraft(
                    id = s.editingId,
                    type = s.type,
                    amountCents = cents,
                    categoryId = s.categoryId,
                    accountId = s.accountId,
                    toAccountId = s.toAccountId,
                    feeCents = if (s.type == TxnType.TRANSFER) s.feeCents else 0,
                    dateTime = s.dateTime,
                    note = s.note,
                )
            viewModelScope.launch {
                _state.update { it.copy(saving = true, error = null) }
                when (val result = saveTxn(draft)) {
                    is AppResult.Success -> {
                        runCatching { settings.setLastTxnType(s.type) }
                        _state.update { it.copy(saving = false) }
                        _events.emit(EditorEvent.Saved(result.data))
                    }
                    is AppResult.Failure -> {
                        _state.update { it.copy(saving = false, error = result.error.message) }
                    }
                }
            }
        }
    }
