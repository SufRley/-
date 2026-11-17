package com.xxjz.ledger.feature.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xxjz.ledger.core.common.MoneyFormatter
import com.xxjz.ledger.domain.model.Category
import com.xxjz.ledger.domain.model.DebtAction
import com.xxjz.ledger.domain.model.DebtDirection
import com.xxjz.ledger.domain.model.InstallmentSchedule
import com.xxjz.ledger.domain.model.Tag
import com.xxjz.ledger.domain.model.TxnType
import com.xxjz.ledger.domain.repository.AccountRepository
import com.xxjz.ledger.domain.repository.CategoryRepository
import com.xxjz.ledger.domain.repository.InstallmentRepository
import com.xxjz.ledger.domain.repository.MerchantRepository
import com.xxjz.ledger.domain.repository.SettingsRepository
import com.xxjz.ledger.domain.repository.TagRepository
import com.xxjz.ledger.domain.repository.TemplateRepository
import com.xxjz.ledger.domain.repository.TxnRepository
import com.xxjz.ledger.domain.usecase.RecordDebtOpUseCase
import com.xxjz.ledger.domain.usecase.RefundTxnUseCase
import com.xxjz.ledger.domain.usecase.SaveDebtAccountUseCase
import com.xxjz.ledger.domain.usecase.SaveTxnUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 记账面板 ViewModel：新建 / 编辑共用，靠 [loadForEdit] 区分。
 *
 * 状态定义与派生属性见 [EditorUiState]；预选记忆见 [EditorMemoryDelegate]；
 * 提交保存见 [EditorSubmitDelegate]；三者通过 [EditorRuntime] 共享同一份状态与依赖。
 * 公开 API 与构造签名保持不变，UI 与测试无需改动。
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
        private val merchantRepository: MerchantRepository,
        private val installmentRepository: InstallmentRepository,
        private val refundTxn: RefundTxnUseCase,
    ) : ViewModel() {
        private val runtime =
            EditorRuntime(
                state = MutableStateFlow(EditorUiState()),
                events = MutableSharedFlow(extraBufferCapacity = 4),
                scope = viewModelScope,
                txnRepository = txnRepository,
                categoryRepository = categoryRepository,
                accountRepository = accountRepository,
                templateRepository = templateRepository,
                saveTxn = saveTxn,
                recordDebtOp = recordDebtOp,
                saveDebtAccount = saveDebtAccount,
                settings = settings,
                tagRepository = tagRepository,
                merchantRepository = merchantRepository,
                installmentRepository = installmentRepository,
                refundTxn = refundTxn,
            )
        private val _state get() = runtime.state

        val state: StateFlow<EditorUiState> = runtime.state.asStateFlow()
        val events: SharedFlow<EditorEvent> = runtime.events.asSharedFlow()

        private val memoryDelegate = EditorMemoryDelegate(runtime)
        private val submitDelegate = EditorSubmitDelegate(runtime, memoryDelegate)

        init {
            observeCategories()
            observeAccounts()
            observeTags()
            observeTemplates()
            refreshMerchants()
            memoryDelegate.loadPreferences()
        }

        /** 把记忆套到未动过的字段上——预选逻辑见 [EditorMemoryDelegate]。 */
        private fun applyMemory() = memoryDelegate.applyMemory()

        /** 分类优先级——预选逻辑见 [EditorMemoryDelegate]。 */
        private fun preferredCategory(
            categories: List<Category>,
            type: TxnType,
        ) = memoryDelegate.preferredCategory(categories, type)

        private fun observeTags() {
            viewModelScope.launch {
                tagRepository.observeAll().collect { list -> _state.update { it.copy(tags = list) } }
            }
        }

        private fun observeTemplates() {
            viewModelScope.launch {
                templateRepository.observeAll().collect { list -> _state.update { it.copy(templates = list) } }
            }
        }

        /**
         * 拉一次商家候选。
         *
         * 商家是「用过的才出现」，没有 Flow 版本；新建一个商家后必须重新拉一次，
         * 否则刚建的「星巴克」要等下次进页面才在抽屉里。
         */
        private fun refreshMerchants() {
            viewModelScope.launch {
                val list = runCatching { merchantRepository.suggest(MERCHANT_SUGGEST_LIMIT) }.getOrDefault(emptyList())
                _state.update { it.copy(merchants = list) }
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
            val refunded = runCatching { txnRepository.refundedTotal(txn.id) }.getOrDefault(0L)
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
                    merchantId = txn.merchantId,
                    pendingReimbursement = txn.pendingReimbursement,
                    excludedFromStats = txn.excludedFromStats,
                    refundedCents = refunded,
                    refundText = "",
                    installmentPeriods = null,
                    dirty = false,
                )
            }
            // 编辑一笔从没碰过的商家时，候选里可能还没有它，补一次让胶囊能显示名字
            refreshMerchants()
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
        fun submitOrEvaluate(stayOpen: Boolean = false) {
            if (!_state.value.calc.evaluated && !_state.value.splitMode) {
                _state.update { it.copy(calc = CalcEngine.evaluate(it.calc)) }
            }
            submit(stayOpen)
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
        fun createDebtAccount(name: String) = submitDelegate.createDebtAccount(name)

        fun selectCategory(id: String) {
            runtime.sessionPicks[_state.value.type] = id
            _state.update { it.copy(categoryId = id, dirty = true) }
        }

        fun selectAccount(id: String) {
            runtime.sessionAccountId = id
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

        /**
         * 切换单选 / 组合。切回单选时清空分摊，避免残留金额悄悄生效。
         *
         * v1.9.5 第 1 条起由两段式控件显式传 true / false，不再有「翻转」这个入口：
         * 翻转的语义是「点一下变反」，而用户点的是具体那一段，重复点同段不该把状态翻回去。
         */
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

        fun setMerchant(id: String?) = _state.update { it.copy(merchantId = id, dirty = true) }

        /**
         * 抽屉里现输入一个新商家。
         *
         * `findOrCreate` 保证了同名不会建两条；建完立刻回填并刷新候选，
         * 否则用户回到页面会发现抽屉里还是没有它。
         */
        fun createMerchant(name: String) {
            val trimmed = name.trim()
            if (trimmed.isEmpty()) return
            viewModelScope.launch {
                val merchant = runCatching { merchantRepository.findOrCreate(trimmed) }.getOrNull()
                refreshMerchants()
                merchant?.let { m -> _state.update { it.copy(merchantId = m.id, dirty = true) } }
            }
        }

        fun togglePendingReimbursement() = _state.update { it.copy(pendingReimbursement = !it.pendingReimbursement, dirty = true) }

        fun toggleExcludedFromStats() = _state.update { it.copy(excludedFromStats = !it.excludedFromStats, dirty = true) }

        fun setRefundText(text: String) {
            val filtered = text.filter { it.isDigit() || it == '.' }.take(12)
            _state.update { it.copy(refundText = filtered) }
        }

        fun clearRefundText() = _state.update { it.copy(refundText = "") }

        /**
         * 设置分期期数。
         *
         * 传 null 或非法值（< 2、> 60）都视为「不分期」——与其弹个错误挡住用户，
         * 不如让他继续记账；真要分期，页面上会写明合法区间。
         */
        fun setInstallmentPeriods(periods: Int?) {
            val valid = periods?.takeIf { InstallmentSchedule.isValidPeriods(it) }
            _state.update { it.copy(installmentPeriods = valid, dirty = true) }
        }

        /**
         * 对当前这笔支出发起退款（PRD 6.8 第 6 枚）。
         *
         * 退款是**另一笔收入流水**，不动原支出：金额、日期、分类都保持原样，
         * 只在 `refundOfTxnId` 上建立关联。所以这里保存成功后只刷新「已退金额」，
         * 页面上的金额输入框一个字符都不改。
         */
        fun submitRefund() = submitDelegate.submitRefund()

        /** 常用标签：按使用次数取前 10 个（标签抽屉默认也按这个顺序排）。 */
        fun quickTags(): List<Tag> = _state.value.tags.sortedByDescending { it.usageCount }.take(10)

        // ---------- 保存 ----------

        fun submit(stayOpen: Boolean = false) = submitDelegate.submit(stayOpen)
    }
