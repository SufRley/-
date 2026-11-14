package com.xxjz.ledger.feature.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xxjz.ledger.core.common.AppResult
import com.xxjz.ledger.core.common.DateUtils
import com.xxjz.ledger.core.common.MoneyFormatter
import com.xxjz.ledger.data.datastore.DefaultTimeMode
import com.xxjz.ledger.data.datastore.SettingsRepository
import com.xxjz.ledger.domain.model.Account
import com.xxjz.ledger.domain.model.AccountType
import com.xxjz.ledger.domain.model.Category
import com.xxjz.ledger.domain.model.DebtAction
import com.xxjz.ledger.domain.model.DebtDirection
import com.xxjz.ledger.domain.model.Tag
import com.xxjz.ledger.domain.model.TxnType
import com.xxjz.ledger.domain.repository.AccountRepository
import com.xxjz.ledger.domain.repository.CategoryRepository
import com.xxjz.ledger.domain.repository.TagRepository
import com.xxjz.ledger.domain.repository.TemplateRepository
import com.xxjz.ledger.domain.repository.TxnRepository
import com.xxjz.ledger.domain.usecase.DebtAccountDraft
import com.xxjz.ledger.domain.usecase.DebtOpDraft
import com.xxjz.ledger.domain.usecase.RecordDebtOpUseCase
import com.xxjz.ledger.domain.usecase.SaveDebtAccountUseCase
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

/** 快捷分类格子的数量：3 行 × 4 列（PRD v1.9.1 第 5 条）。 */
const val QUICK_CATEGORY_SLOTS = 12

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
    /** 支出/收入/转账的账户；借贷类型下是**资金账户**（钱从谁那里进出）。 */
    val accountId: String? = null,
    val toAccountId: String? = null,
    /**
     * 借贷动作（v1.9.0 / PRD 3）：只决定这一次记的是借出还是借入，
     * 不再限制账户可选范围——同一个人的账户可以借钱也可以还钱。
     */
    val debtDirection: DebtDirection = DebtDirection.LEND,
    /** 借贷账户（对方）。 */
    val debtAccountId: String? = null,
    val dateTime: Long = System.currentTimeMillis(),
    val note: String = "",
    val feeText: String = "",
    val categories: List<Category> = emptyList(),
    val accounts: List<Account> = emptyList(),
    val saving: Boolean = false,
    val error: String? = null,
    /** 用户是否动过内容，用于误触关闭保护（AC-09）。 */
    val dirty: Boolean = false,
    /** 组合支付开关（PRD v1.9.1）：一笔消费由多个账户共同出账。 */
    val splitMode: Boolean = false,
    /** 组合支付时各账户承担的金额（分），键为账户 id。 */
    val splits: Map<String, Long> = emptyMap(),
    /** 收据照片本地路径（PRD v1.9.1）。 */
    val photoPath: String? = null,
    /** 全部标签，供「常用标签」两行展示。 */
    val tags: List<Tag> = emptyList(),
    val selectedTagIds: Set<String> = emptySet(),
) {
    val amountCents: Long? get() = CalcEngine.centsOf(calc)

    /** 组合支付的合计金额；未填任何账户时为 0。 */
    val splitTotal: Long get() = splits.values.sum()

    /**
     * 真正要保存的金额：组合模式下由各账户合计决定，单选模式下用计算器输入。
     *
     * 组合支付时计算器仍可拨，但不参与结算——否则「各账户加起来 100、计算器写 200」
     * 会出现两套真相，用户不知道哪个生效。
     */
    val effectiveAmount: Long?
        get() = if (splitMode) splitTotal.takeIf { it > 0L } else amountCents

    /**
     * 3 行 4 列快捷分类（PRD v1.9.1 第 5 条）。
     *
     * 排序规则：用过越多次越靠前（常用优先），次数相同按 [Category.sortOrder] 保持原有次序——
     * 不这么做的话，没记过账的用户看到的 12 个格子会是随机顺序，反而更难找。
     */
    val quickCategories: List<Category>
        get() {
            if (type != TxnType.EXPENSE && type != TxnType.INCOME) return emptyList()
            return categories
                .asSequence()
                .filter { it.type == type && !it.hidden }
                .sortedWith(compareByDescending<Category> { it.usageCount }.thenBy { it.sortOrder })
                .take(QUICK_CATEGORY_SLOTS)
                .toList()
        }

    val feeCents: Long get() = MoneyFormatter.yuanToCents(feeText) ?: 0L

    val categoryName: String? get() = categories.firstOrNull { it.id == categoryId }?.name

    val accountName: String? get() = accounts.firstOrNull { it.id == accountId }?.name

    val toAccountName: String? get() = accounts.firstOrNull { it.id == toAccountId }?.name

    val debtAccountName: String? get() = accounts.firstOrNull { it.id == debtAccountId }?.name

    /** 可选的借贷账户：全部借贷账户，不再按方向过滤（v1.9.0 净值模型）。 */
    val debtAccounts: List<Account> get() = accounts.filter { it.type == AccountType.DEBT }

    /** 资金账户：借贷账户不能当资金账户，否则等于自己借给自己。 */
    val fundAccounts: List<Account> get() = accounts.filter { it.type != AccountType.DEBT }

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

/** 从设置里读回来的「上次用的」，新建时按它预选（v1.9.3 第 2 条）。 */
private data class EditorMemory(
    val expenseCategoryId: String? = null,
    val incomeCategoryId: String? = null,
    val payAccountId: String? = null,
    val defaultAccountId: String? = null,
) {
    fun categoryOf(type: TxnType): String? =
        when (type) {
            TxnType.EXPENSE -> expenseCategoryId
            TxnType.INCOME -> incomeCategoryId
            TxnType.TRANSFER, TxnType.DEBT -> null
        }
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
        private val recordDebtOp: RecordDebtOpUseCase,
        private val saveDebtAccount: SaveDebtAccountUseCase,
        private val settings: SettingsRepository,
        private val tagRepository: TagRepository,
    ) : ViewModel() {
        private val _state = MutableStateFlow(EditorUiState())
        val state: StateFlow<EditorUiState> = _state.asStateFlow()

        private val _events = MutableSharedFlow<EditorEvent>(extraBufferCapacity = 4)
        val events: SharedFlow<EditorEvent> = _events.asSharedFlow()

        /** 上次保存时用的值（v1.9.3 第 2 条），新建时用来预选。 */
        private var memory: EditorMemory? = null

        /**
         * 本次页面生命周期内选过的分类，按类型分开记。
         *
         * 它比 [memory] 优先：用户刚在支出里点过「买菜」，切到收入再切回来，
         * 期望看到的是「买菜」，而不是三天前存的「晚餐」。
         */
        private val sessionPicks = mutableMapOf<TxnType, String?>()

        /** 本次页面内选过的付款账户，优先级高于持久化记忆。 */
        private var sessionAccountId: String? = null

        init {
            observeCategories()
            observeAccounts()
            observeTags()
            loadPreferences()
        }

        /**
         * 读一次设置：默认时间 + 记忆的分类/账户。
         *
         * 合并成一次 `settings.current()` 调用，别为了两个用途读两遍 DataStore。
         */
        private fun loadPreferences() {
            viewModelScope.launch {
                val snapshot = runCatching { settings.current() }.getOrNull() ?: return@launch
                memory =
                    EditorMemory(
                        expenseCategoryId = snapshot.lastExpenseCategoryId,
                        incomeCategoryId = snapshot.lastIncomeCategoryId,
                        payAccountId = snapshot.lastPayAccountId,
                        defaultAccountId = snapshot.defaultAccountId,
                    )
                if (snapshot.defaultTimeMode == DefaultTimeMode.FIXED) {
                    val minutes = snapshot.fixedTimeMinutes
                    val time = LocalTime.of(minutes / 60 % 24, minutes % 60)
                    _state.update { it.copy(dateTime = DateUtils.toMillis(DateUtils.today(), time)) }
                }
                applyMemory()
            }
        }

        /**
         * 把记忆套到「还没被用户动过」的字段上。
         *
         * 分类和账户是两条独立的 Flow，谁先到都得试一次，所以这个方法会被调用多次；
         * 它只填空值，绝不覆盖用户已经选好的东西，也不覆盖编辑模式带进来的原值。
         */
        private fun applyMemory() {
            val m = memory ?: return
            _state.update { s ->
                if (s.editingId != null) return@update s
                s.copy(
                    categoryId = s.categoryId ?: preferredCategory(s.categories, s.type),
                    accountId = s.accountId ?: preferredAccount(s.accounts, m),
                )
            }
        }

        /**
         * 分类优先级：本次会话选过的 → 持久化记忆 → 该类型里用得最多的。
         *
         * 最后那级兜底是为了「从没记过账的新用户」也有个合理默认，而不是空白。
         */
        private fun preferredCategory(
            categories: List<Category>,
            type: TxnType,
        ): String? {
            if (type != TxnType.EXPENSE && type != TxnType.INCOME) return null
            val candidates = categories.filter { it.type == type && !it.hidden }
            if (candidates.isEmpty()) return null
            val remembered = sessionPicks[type] ?: memory?.categoryOf(type)
            return candidates.firstOrNull { it.id == remembered }?.id
                ?: candidates
                    .sortedWith(compareByDescending<Category> { it.usageCount }.thenBy { it.sortOrder })
                    .first()
                    .id
        }

        /** 账户优先级：本次会话选过的 → 上次实际用的 → 设置里的默认账户 → 唯一资金账户。 */
        private fun preferredAccount(
            accounts: List<Account>,
            m: EditorMemory,
        ): String? {
            val funds = accounts.filter { it.type != AccountType.DEBT }
            if (funds.isEmpty()) return null
            val remembered = sessionAccountId ?: m.payAccountId
            return funds.firstOrNull { it.id == remembered }?.id
                ?: funds.firstOrNull { it.id == m.defaultAccountId }?.id
                ?: funds.singleOrNull()?.id
        }

        /**
         * 保存成功后记住这次的选择。
         *
         * 只在新建时写：老账目的分类不代表用户当前习惯，写进去等于用历史污染记忆。
         * 组合支付记「出钱最多的那个」账户，那才是下次真正会用的。
         */
        private suspend fun rememberSelection(s: EditorUiState) {
            runCatching { settings.setLastTxnType(s.type) }
            if (s.editingId != null) return
            runCatching {
                if (s.type == TxnType.EXPENSE || s.type == TxnType.INCOME) {
                    settings.setLastCategory(s.type, s.categoryId)
                }
                val payer = if (s.splitMode) s.splits.maxByOrNull { it.value }?.key else s.accountId
                payer?.let { settings.setLastPayAccount(it) }
            }
        }

        private fun observeTags() {
            viewModelScope.launch {
                tagRepository.observeAll().collect { list -> _state.update { it.copy(tags = list) } }
            }
        }

        private fun observeCategories() {
            viewModelScope.launch {
                categoryRepository.observeAll().collect { list ->
                    _state.update { it.copy(categories = list) }
                    // 分类可能比账户先到，两边都要试着套一次记忆
                    applyMemory()
                }
            }
        }

        private fun observeAccounts() {
            viewModelScope.launch {
                accountRepository.observeWithBalances().collect { list ->
                    _state.update { it.copy(accounts = list) }
                    // 不预填第一个账户：以前 chip 直接显示「现金」，用户根本看不出那是个
                    // 账户选择器，于是「支出不能选账户」（v1.8.1 #4）。
                    // 预选规则统一收在 preferredAccount 里（v1.9.3 第 2 条加了记忆）
                    applyMemory()
                }
            }
        }

        /**
         * 编辑模式预填。
         *
         * v1.9.1 补齐了三处以前漏掉的字段：借贷方向（以前编辑一笔借入会被悄悄改成借出）、
         * 照片、已选标签。编辑的本质是「原样打开」，任何一项丢失都算数据损坏的观感。
         */
        suspend fun loadForEdit(txnId: String) {
            val txn = txnRepository.byId(txnId) ?: return
            val tagIds = runCatching { tagRepository.tagIdsOf(txn.id) }.getOrDefault(emptyList()).toSet()
            _state.update {
                it.copy(
                    editingId = txn.id,
                    type = txn.type,
                    calc = CalcState(buffer = MoneyFormatter.centsToPlain(txn.amount)),
                    categoryId = txn.categoryId,
                    accountId = txn.accountId,
                    toAccountId = txn.toAccountId,
                    // 借贷流水里 accountId 是对方、toAccountId 是资金账户，与新建时的字段语义相反
                    debtAccountId = if (txn.type == TxnType.DEBT) txn.accountId else it.debtAccountId,
                    debtDirection =
                        if (txn.type == TxnType.DEBT && txn.debtAction == DebtAction.BORROW_IN) {
                            DebtDirection.BORROW
                        } else {
                            DebtDirection.LEND
                        },
                    dateTime = txn.dateTime,
                    note = txn.note,
                    feeText = if (txn.fee > 0) MoneyFormatter.centsToPlain(txn.fee) else "",
                    photoPath = txn.photoPath,
                    selectedTagIds = tagIds,
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
                    // 换类型必须换分类：支出分类拿去记收入会存出「收入 / 晚餐」这种错账。
                    // 取新类型自己的记忆值，切回来时还能恢复刚才选的（v1.9.3 第 2 条）
                    categoryId = preferredCategory(it.categories, type),
                    // 借贷不选分类，资金账户也不能是借贷账户
                    accountId = if (type == TxnType.DEBT) it.fundAccounts.firstOrNull()?.id else it.accountId,
                    debtAccountId = if (type == TxnType.DEBT) it.debtAccounts.firstOrNull()?.id else null,
                    // 组合支付只对收支有意义：转账与借贷是「一进一出」的一对，拆开就成了两笔错账
                    splitMode = it.splitMode && type != TxnType.TRANSFER && type != TxnType.DEBT,
                    splits = if (type == TxnType.TRANSFER || type == TxnType.DEBT) emptyMap() else it.splits,
                    dirty = true,
                )
            }
        }

        /**
         * 切换借贷动作。v1.9.0 起对方账户不再按方向过滤，已选账户予以保留。
         */
        fun setDebtDirection(direction: DebtDirection) {
            if (direction == _state.value.debtDirection) return
            _state.update { it.copy(debtDirection = direction, dirty = true) }
        }

        fun selectDebtAccount(id: String) = _state.update { it.copy(debtAccountId = id, dirty = true) }

        /**
         * 记账面板里「还没有借贷账户」时一键新建（PRD 8.3）。
         *
         * 只建人、不带本金：本金在金额区填，保存时一并落成一笔借出/借入流水。
         */
        fun createDebtAccount(name: String) {
            val trimmed = name.trim()
            if (trimmed.isEmpty()) {
                _state.update { it.copy(error = "请填写对方姓名") }
                return
            }
            viewModelScope.launch {
                val result =
                    saveDebtAccount(
                        DebtAccountDraft(
                            counterparty = trimmed,
                            firstAction =
                                if (_state.value.debtDirection == DebtDirection.LEND) {
                                    DebtAction.LEND_OUT
                                } else {
                                    DebtAction.BORROW_IN
                                },
                        ),
                    )
                when (result) {
                    is AppResult.Success -> {
                        _state.update { it.copy(debtAccountId = result.data, error = null, dirty = true) }
                        _events.emit(EditorEvent.Message("已新建借贷账户「$trimmed」"))
                    }
                    is AppResult.Failure -> _state.update { it.copy(error = result.error.message) }
                }
            }
        }

        fun selectCategory(id: String) {
            sessionPicks[_state.value.type] = id
            _state.update { it.copy(categoryId = id, dirty = true) }
        }

        fun selectAccount(id: String) {
            sessionAccountId = id
            _state.update {
                val to = if (it.type == TxnType.TRANSFER && it.toAccountId == id) it.accountId else it.toAccountId
                it.copy(accountId = id, toAccountId = to, dirty = true)
            }
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

        // ---------- 组合支付 / 照片 / 标签（PRD v1.9.1 第 5 条） ----------

        /** 切换单选 / 组合。切回单选时清空分摊，避免残留金额悄悄生效。 */
        fun setSplitMode(on: Boolean) =
            _state.update {
                it.copy(splitMode = on, splits = if (on) it.splits else emptyMap(), dirty = true)
            }

        /**
         * 设置某个账户在组合支付里出多少钱。
         *
         * 传空串或 0 视为把这个账户从组合里去掉——用户清空输入框的意图就是「不用它付」。
         */
        fun setSplitAmount(
            accountId: String,
            text: String,
        ) {
            val cents = MoneyFormatter.yuanToCents(text) ?: 0L
            _state.update {
                val next = it.splits.toMutableMap()
                if (cents <= 0L) next.remove(accountId) else next[accountId] = cents
                it.copy(splits = next, dirty = true)
            }
        }

        fun removeSplit(accountId: String) = _state.update { it.copy(splits = it.splits - accountId, dirty = true) }

        fun setPhoto(path: String?) = _state.update { it.copy(photoPath = path, dirty = true) }

        fun toggleTag(tagId: String) =
            _state.update {
                val next = if (tagId in it.selectedTagIds) it.selectedTagIds - tagId else it.selectedTagIds + tagId
                it.copy(selectedTagIds = next, dirty = true)
            }

        /** 常用标签：按使用次数取前 10 个，两行横向滑动刚好放得下。 */
        fun quickTags(): List<Tag> = _state.value.tags.sortedByDescending { it.usageCount }.take(10)

        // ---------- 保存 ----------

        fun submit() {
            val s = _state.value
            if (s.saving) return
            // 组合支付时金额由各账户合计决定，不吃计算器的值
            val cents = s.effectiveAmount
            if (cents == null) {
                val reason =
                    if (s.splitMode) "请至少给两个账户填金额" else (s.calc.error ?: "请输入大于 0 的金额")
                _state.update { it.copy(error = reason) }
                return
            }
            // 借贷走独立用例：它不是「记一笔收支」，而是一次资金往来，
            // 一条流水要同时改动借贷账户与资金账户（PRD 2.2）
            if (s.type == TxnType.DEBT) {
                submitDebt(s, cents)
                return
            }
            if (s.splitMode) {
                submitSplit(s)
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
                    photoPath = s.photoPath,
                )
            viewModelScope.launch {
                _state.update { it.copy(saving = true, error = null) }
                when (val result = saveTxn(draft)) {
                    is AppResult.Success -> {
                        rememberSelection(s)
                        saveTags(result.data, s.selectedTagIds)
                        _state.update { it.copy(saving = false) }
                        _events.emit(EditorEvent.Saved(result.data))
                    }
                    is AppResult.Failure -> {
                        _state.update { it.copy(saving = false, error = result.error.message) }
                    }
                }
            }
        }

        /**
         * 组合支付保存：每个账户各生成一条流水（PRD v1.9.1 第 5 条，用户选定「各显示 1 条」）。
         *
         * 这样余额自然各扣各的，明细里也看得出「这笔到底谁付了多少」；
         * 备注追加「组合 i/n」，方便日后一眼认出它们本是一笔。
         *
         * 编辑模式下先把原流水软删除再落新的：一笔账拆成 N 条之后，原来那条已经没有意义了。
         * 放回收站而不是物理删除，用户后悔还能捞回来。
         */
        private fun submitSplit(s: EditorUiState) {
            val parts = s.splits.filterValues { it > 0L }
            if (parts.size < 2) {
                _state.update { it.copy(error = "组合支付至少要两个账户各出一部分") }
                return
            }
            viewModelScope.launch {
                _state.update { it.copy(saving = true, error = null) }
                var lastId: String? = null
                var index = 0
                for ((accountId, cents) in parts) {
                    index += 1
                    val note =
                        if (parts.size > 1) {
                            "${s.note}（组合 $index/${parts.size}）".trim().take(200)
                        } else {
                            s.note
                        }
                    val draft =
                        TxnDraft(
                            type = s.type,
                            amountCents = cents,
                            categoryId = s.categoryId,
                            accountId = accountId,
                            dateTime = s.dateTime,
                            note = note,
                            photoPath = s.photoPath,
                        )
                    when (val result = saveTxn(draft)) {
                        is AppResult.Success -> {
                            lastId = result.data
                            saveTags(result.data, s.selectedTagIds)
                        }
                        is AppResult.Failure -> {
                            _state.update { it.copy(saving = false, error = result.error.message) }
                            return@launch
                        }
                    }
                }
                val finalId = lastId
                if (finalId != null) {
                    s.editingId?.let { runCatching { txnRepository.softDelete(it, System.currentTimeMillis()) } }
                    rememberSelection(s)
                    _state.update { it.copy(saving = false) }
                    _events.emit(EditorEvent.Saved(finalId))
                }
            }
        }

        /** 标签写入：失败不阻断保存，标签丢总比账没记上好。 */
        private suspend fun saveTags(
            txnId: String,
            tagIds: Set<String>,
        ) {
            if (tagIds.isEmpty()) return
            runCatching { tagRepository.setTxnTags(txnId, tagIds.toList()) }
        }

        /**
         * 借贷保存：只支持「新的借出 / 新的借入」。
         *
         * 收款与还款是对**已有欠款**的动作，必须先看余额，放在借贷账户详情页做（PRD 2.5）。
         * 这样记账面板里只有一条直线：选方向 → 选对方 → 选资金账户 → 金额 → 保存。
         */
        private fun submitDebt(
            s: EditorUiState,
            cents: Long,
        ) {
            val debtId = s.debtAccountId
            if (debtId == null) {
                _state.update { it.copy(error = "请选择借贷账户（对方）") }
                return
            }
            val fundId = s.accountId
            if (fundId == null) {
                _state.update { it.copy(error = "请选择资金账户") }
                return
            }
            val action =
                if (s.debtDirection == DebtDirection.LEND) DebtAction.LEND_OUT else DebtAction.BORROW_IN
            viewModelScope.launch {
                _state.update { it.copy(saving = true, error = null) }
                val result =
                    recordDebtOp(
                        DebtOpDraft(
                            action = action,
                            debtAccountId = debtId,
                            fundAccountId = fundId,
                            amountCents = cents,
                            at = s.dateTime,
                            note = s.note,
                        ),
                    )
                when (result) {
                    is AppResult.Success -> {
                        runCatching { settings.setLastTxnType(TxnType.DEBT) }
                        _state.update { it.copy(saving = false) }
                        _events.emit(EditorEvent.Saved(result.data))
                    }
                    is AppResult.Failure ->
                        _state.update { it.copy(saving = false, error = result.error.message) }
                }
            }
        }
    }
