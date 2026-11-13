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
    val debtId: String? = null,
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
)

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

/** 借贷记录。剩余额 = 本金 - SUM(还款流水)。 */
data class Debt(
    val id: String,
    val direction: DebtDirection,
    val counterparty: String,
    /** 本金（分）。 */
    val principal: Long,
    val accountId: String? = null,
    val startAt: Long,
    val dueAt: Long? = null,
    val note: String = "",
    val settled: Boolean = false,
    /** 坏账：经确认收不回，计入损失但不影响「已还」口径（AC-65）。 */
    val writtenOff: Boolean = false,
    /** 已还 / 已收（分），由聚合查询填充。 */
    val repaid: Long = 0,
) {
    val remaining: Long get() = (principal - repaid).coerceAtLeast(0)

    /** 未结清且已过还款日 → 逾期标红（AC-63）。 */
    fun isOverdue(now: Long = System.currentTimeMillis()): Boolean = !settled && !writtenOff && dueAt != null && dueAt < now
}

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
