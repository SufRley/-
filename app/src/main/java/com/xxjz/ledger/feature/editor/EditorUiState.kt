package com.xxjz.ledger.feature.editor

import com.xxjz.ledger.core.common.MoneyFormatter
import com.xxjz.ledger.domain.model.Account
import com.xxjz.ledger.domain.model.AccountType
import com.xxjz.ledger.domain.model.Category
import com.xxjz.ledger.domain.model.DebtDirection
import com.xxjz.ledger.domain.model.Merchant
import com.xxjz.ledger.domain.model.Tag
import com.xxjz.ledger.domain.model.Template
import com.xxjz.ledger.domain.model.TxnType
import com.xxjz.ledger.domain.usecase.FundPayable

/** 快捷分类格子的数量：3 行 × 4 列（PRD v1.9.1 第 5 条）。 */
const val QUICK_CATEGORY_SLOTS = 12

/** 记账面板一次性事件。 */
sealed interface EditorEvent {
    data class Saved(val id: String) : EditorEvent

    /**
     * 「再记」：已经落库但页面不关，UI 弹一条 Snackbar 就好（v1.9.3 第 6.9 条）。
     *
     * 金额要跟着事件走：页面此时已经被清空，读 state 只会拿到 0。
     */
    data class SavedAndNext(
        val id: String,
        val amountCents: Long,
    ) : EditorEvent

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
    /** 全部标签，供标签抽屉多选。 */
    val tags: List<Tag> = emptyList(),
    val selectedTagIds: Set<String> = emptySet(),
    /** 全部模板，页头爱心弹层直接列（v1.9.3 第 7 条）。 */
    val templates: List<Template> = emptyList(),
    /** 商家候选，按使用次数倒序（商家的「常用」排序）。 */
    val merchants: List<Merchant> = emptyList(),
    val merchantId: String? = null,
    /** 待报销：只有支出有意义。 */
    val pendingReimbursement: Boolean = false,
    /** 不计入收支统计。 */
    val excludedFromStats: Boolean = false,
    /**
     * 已退金额（分），编辑一笔支出时从库实时读。
     *
     * 只读：[com.xxjz.ledger.domain.usecase.RefundTxnUseCase] 会生成**另一笔收入流水**，
     * 不能把退款额从当前这笔的金额里扣掉——那等于篡改已发生的支出。
     */
    val refundedCents: Long = 0,
    /** 本次要发起的退款金额（元文本），空表示不退款。 */
    val refundText: String = "",
    /** 新建支出时顺带建的分期期数；null 表示不分期。 */
    val installmentPeriods: Int? = null,
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

    /** 当前选中的付款（或资金）账户对象，供口径校验与图标取用。 */
    val payAccount: Account? get() = accounts.firstOrNull { it.id == accountId }

    val toAccount: Account? get() = accounts.firstOrNull { it.id == toAccountId }

    val accountName: String? get() = accounts.firstOrNull { it.id == accountId }?.name

    val toAccountName: String? get() = accounts.firstOrNull { it.id == toAccountId }?.name

    val debtAccountName: String? get() = accounts.firstOrNull { it.id == debtAccountId }?.name

    /** 借贷的对方账户：全部借贷账户，不再按方向过滤（v1.9.0 净值模型）。 */
    val debtAccounts: List<Account> get() = accounts.filter { it.type == AccountType.DEBT }

    /**
     * 付款账户：现金 / 储蓄卡 / 信用（v1.9.5 第 11 条）。
     *
     * 投资账户被排除：它的余额是持仓里剩下的现金，不是可随时花掉的活钱。
     * 从股票账户里扣一顿饭钱，账务上是错的——买入该走转账，不该走支出。
     */
    val payAccounts: List<Account> get() = FundPayable.payableAccounts(accounts)

    /**
     * 转账两端：在付款账户基础上加投资（v1.9.5 第 3 条）。
     *
     * 投资账户的买入与赎回都是转账，两端都得通，否则钱进不去也出不来。
     */
    val transferAccounts: List<Account> get() = FundPayable.transferAccounts(accounts)

    /** 借贷的资金账户：钱从哪儿出，与付款同口径，不能用投资。 */
    val fundAccounts: List<Account> get() = payAccounts

    /**
     * 收入能落到哪些账户（v1.9.5 第 11 条的边界）。
     *
     * 收入不是"付款"，所以投资账户可以收——股息、分红、赎回款都真实存在。
     * 但借贷账户不行：它的余额表示"对方欠我多少"，往里面记一笔收入会把欠款算乱。
     * 口径恰好与转账两端一致，故直接复用。
     */
    val incomeAccounts: List<Account> get() = transferAccounts

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

    val merchantName: String? get() = merchants.firstOrNull { it.id == merchantId }?.name

    val refundCents: Long get() = MoneyFormatter.yuanToCents(refundText) ?: 0L

    /** 还能不能再退：必须是编辑中的支出，且累计退款还没退完。 */
    val refundable: Boolean get() = editingId != null && type == TxnType.EXPENSE && refundedCents < (amountCents ?: 0L)

    /**
     * 分期只对「支出 + 信用账户」开放。
     *
     * 储蓄卡分期在现实里不存在；支出之外的类型（收入/转账/借贷）也没有「分几期还」的概念。
     * 限定这个条件，胶囊才不会变成一个点了没反应的摆设。
     */
    val installmentAvailable: Boolean
        get() = type == TxnType.EXPENSE && accounts.firstOrNull { it.id == accountId }?.type == AccountType.CREDIT

    /**
     * 保存前置检查：null 表示可以存，否则是「还缺什么」的人话。
     *
     * 键盘上的「保存」按这个置灰，点不动时顺手把这句话弹成 Snackbar——
     * 比一个灰按钮让人猜原因友好得多（PRD 6.9 禁用态）。
     */
    val saveBlocker: String?
        get() =
            when {
                saving -> "正在保存…"
                splitMode && splits.size < 2 -> "组合支付至少要两个账户"
                effectiveAmount == null ->
                    if (splitMode) "请填写各账户金额" else (calc.error ?: "请输入金额")
                type == TxnType.EXPENSE || type == TxnType.INCOME ->
                    when {
                        categoryId == null -> "请选择分类"
                        accountId == null -> if (type == TxnType.INCOME) "请选择收款账户" else "请选择账户"
                        // 历史数据里可能有「支出 + 投资账户」：编辑时不偷偷改账户，只说清为什么不给存
                        type == TxnType.EXPENSE && !FundPayable.canPay(payAccount) ->
                            "原账户不可用于付款，请重新选择"
                        else -> null
                    }
                type == TxnType.DEBT ->
                    when {
                        debtAccountId == null -> "请选择借贷账户"
                        accountId == null -> "请选择资金账户"
                        !FundPayable.canPay(payAccount) -> "资金账户不能是投资或借贷账户"
                        else -> null
                    }
                accountId == null -> "请选择账户"
                type == TxnType.TRANSFER && toAccountId == null -> "请选择转入账户"
                type == TxnType.TRANSFER && !FundPayable.canTransfer(payAccount) -> "转出账户不可用，请重新选择"
                type == TxnType.TRANSFER && !FundPayable.canTransfer(toAccount) -> "转入账户不可用，请重新选择"
                else -> null
            }
}
