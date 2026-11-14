package com.xxjz.ledger.domain.model

/**
 * 一笔账目。全系统核心模型。
 *
 * 金额一律以「分」为单位的 Long 存储；[type] 决定哪些字段有效：
 * - EXPENSE / INCOME：`categoryId`、`accountId`
 * - TRANSFER：`accountId`（转出）→ `toAccountId`（转入），`fee` 为手续费
 * - DEBT：`debtId` 指向借贷记录，方向由借贷记录决定
 * - 退款收入：`refundOfTxnId` 指向被退款的原支出
 */
data class Txn(
    val id: String,
    val type: TxnType,
    /** 金额（分），始终为正。 */
    val amount: Long,
    val categoryId: String?,
    val accountId: String?,
    val toAccountId: String? = null,
    /** 转账手续费（分）。 */
    val fee: Long = 0,
    val dateTime: Long,
    /** yyyyMMdd 冗余字段，便于按天/按月分组。 */
    val dateKey: Int,
    val note: String = "",
    val memo: String = "",
    val merchantId: String? = null,
    val merchantName: String? = null,
    val currency: String = "CNY",
    val ledgerId: String? = null,
    /** 不计入收支统计。 */
    val excludedFromStats: Boolean = false,
    /** 待报销。 */
    val pendingReimbursement: Boolean = false,
    /** 已报销金额（分）。 */
    val reimbursedAmount: Long = 0,
    val reimbursedAt: Long? = null,
    /** 退款来源：本笔是哪个支出的退款。 */
    val refundOfTxnId: String? = null,
    val parentTxnId: String? = null,
    /** 旧台账 id（v1.9.0 起仅用于迁移溯源，UI 不再使用）。 */
    val debtId: String? = null,
    /**
     * 借贷动作（v1.9.0 / PRD 2.2），仅 `type = DEBT` 的流水有值。
     *
     * `accountId` 指向**借贷账户**，`toAccountId` 指向**资金账户**；
     * 一条流水同时改动两侧余额，因此四种操作都不改变净资产。
     */
    val debtAction: DebtAction? = null,
    /** 分期计划 id（v1.9.0）：这笔还款冲抵的是哪个分期，普通账单还款为空。 */
    val installmentId: String? = null,
    /** 软删除时间，非空表示在回收站。 */
    val deletedAt: Long? = null,
    val createdAt: Long,
    val updatedAt: Long,
) {
    val isDeleted: Boolean get() = deletedAt != null

    /** 该笔账目对统计的符号：支出记负、收入记正、转账与借贷不计入收支。 */
    val direction: Direction
        get() =
            when (type) {
                TxnType.EXPENSE -> Direction.EXPENSE
                TxnType.INCOME -> Direction.INCOME
                else -> Direction.NEUTRAL
            }
}

/** 账户。余额不落库，由流水实时聚合（见 M3）。 */
data class Account(
    val id: String,
    val name: String,
    val type: AccountType,
    /** 初始余额（分）。 */
    val initialBalance: Long = 0,
    val icon: String = "",
    val color: String = "",
    val cardLimit: Long? = null,
    val billDay: Int? = null,
    val repayDay: Int? = null,
    val hidden: Boolean = false,
    val sortOrder: Int = 0,
    /** 账户余额（分），由聚合查询填充。 */
    val balance: Long = 0,
    /** 投资账户累计成本（分），由转入/转出流水累加，仅 INVEST 使用。 */
    val costCents: Long = 0,
    /** 投资账户当前市值（分），用户手动维护，仅 INVEST 使用。 */
    val marketValueCents: Long = 0,
    /** 投资账户市值是否计入净资产，默认计入。 */
    val includeInNetAssets: Boolean = true,
    /**
     * 借贷方向（v1.9.0 / PRD 2.1）。
     *
     * **v1.9.0 净值模型后仅作历史数据兼容保留，不再参与任何业务逻辑**：
     * 一个借贷账户 = 一个人 = 一本流水账，方向由余额符号表达（正数待收、负数待还）。
     * 字段留着是为了升级时不丢数据、必要时可回溯。
     */
    val debtDirection: DebtDirection? = null,
    /** 备注（v1.9.0，从旧台账搬过来的备注也落在这里）。 */
    val note: String = "",
    /** 借贷约定还款日（v1.9.0），仅借贷账户使用。 */
    val dueAt: Long? = null,
) {
    /** 投资盈亏（分）= 市值 − 累计成本；非投资账户恒为 0。 */
    val profitCents: Long get() = if (type == AccountType.INVEST) marketValueCents - costCents else 0

    /** 投资盈亏率（0..1 的小数），成本为 0 时返回 0 避免除零。 */
    val profitRate: Double get() = if (costCents > 0) profitCents.toDouble() / costCents else 0.0

    /** 是否「我借出」（对方欠我）。v1.9.0 净值模型后仅存档用，业务逻辑请判断 `balance` 符号。 */
    val isLending: Boolean get() = type == AccountType.DEBT && debtDirection != DebtDirection.BORROW

    /** 是否「我借入」（我欠对方）。v1.9.0 */
    val isBorrowing: Boolean get() = type == AccountType.DEBT && debtDirection == DebtDirection.BORROW
}

/** 分类。[parentId] 为空表示大类。 */
data class Category(
    val id: String,
    val name: String,
    val parentId: String? = null,
    val type: TxnType = TxnType.EXPENSE,
    val icon: String = "",
    val color: String = "",
    val sortOrder: Int = 0,
    val hidden: Boolean = false,
    /** 是否置顶到「常用」。 */
    val pinned: Boolean = false,
    /** 使用次数，用于「常用」排序。 */
    val usageCount: Int = 0,
)

/** 分类树节点。 */
data class CategoryNode(
    val category: Category,
    val children: List<Category> = emptyList(),
)

data class Tag(
    val id: String,
    val name: String,
    val color: String = "",
    val usageCount: Int = 0,
)

data class Merchant(
    val id: String,
    val name: String,
    val usageCount: Int = 0,
)

/** 记账模板。 */
data class Template(
    val id: String,
    val name: String,
    val type: TxnType,
    val amount: Long,
    val categoryId: String? = null,
    val accountId: String? = null,
    val toAccountId: String? = null,
    val note: String = "",
    val sortOrder: Int = 0,
)

/** 预算。 */
data class Budget(
    val id: String,
    /** 该版预算生效的起始月份 yyyyMM。 */
    val monthKey: Int,
    val name: String,
    val period: BudgetPeriod = BudgetPeriod.MONTH,
    val scope: BudgetScope = BudgetScope.TOTAL,
    /** 范围选中的分类 id（可以是任意层级），TOTAL 时为空。 */
    val categoryIds: List<String> = emptyList(),
    /** 预算额度（分）。 */
    val amount: Long,
    val enabled: Boolean = true,
    /** 软删除时间，非空表示在回收站。 */
    val deletedAt: Long? = null,
    val sortOrder: Int = 0,
)

/** 周期记账规则。 */
data class RecurringRule(
    val id: String,
    val type: TxnType,
    val amount: Long,
    val categoryId: String? = null,
    val accountId: String? = null,
    val toAccountId: String? = null,
    val note: String = "",
    val period: RecurringPeriod,
    val interval: Int = 1,
    val nextRunAt: Long,
    val endAt: Long? = null,
    val maxTimes: Int? = null,
    val runTimes: Int = 0,
    val paused: Boolean = false,
)

/*
 * 借贷记录模型（v1.9.0 删除）：借贷改为「一人一账户」，余额由 `type = DEBT` 的流水聚合得出，
 * 不再有「本金 - 已还」的独立实体。旧 `DebtEntity` 仍保留在 schema 里供迁移读取。
 */

/** 某个月的收支汇总。 */
data class MonthlySummary(
    val monthKey: Int,
    val income: Long,
    val expense: Long,
    val count: Int,
) {
    val balance: Long get() = income - expense
}

/** 按分类聚合的一行。 */
data class CategorySum(
    val categoryId: String?,
    val categoryName: String,
    val total: Long,
    val count: Int,
)

/** 某一天的收支聚合，用于日历圆点与明细分组头。 */
data class DaySum(
    val dateKey: Int,
    val income: Long,
    val expense: Long,
    val count: Int,
)
