package com.xxjz.ledger.domain.model

/** 记账类型。 */
enum class TxnType { EXPENSE, INCOME, TRANSFER, DEBT }

/**
 * 账户类型：现金 / 储蓄卡 / 信用 / 借贷 / 投资（v1.8.0 按 PRD D1 从 3 类拆为 5 类）。
 *
 * 数据库里以枚举名（`name`）存字符串，读库时统一走 [parseAccountType]，
 * 以便兼容 v1.7.0 及更早的 `FUND` 老数据（一律视为储蓄卡）。
 */
enum class AccountType {
    /** 现金。 */
    CASH,

    /** 储蓄卡、电子钱包等（原 FUND 的迁移目标）。 */
    SAVINGS,

    /** 信用卡、花呗等信用类，余额为负表示欠款。 */
    CREDIT,

    /** 借出 / 借入类账户。 */
    DEBT,

    /** 投资账户：累计成本 + 当前市值双字段，盈亏现场计算（不联网）。 */
    INVEST,
}

/**
 * 账户类型容错解析（v1.8.0）：
 * - 5 个新值直接映射；
 * - `FUND`（≤ v1.7.0 的唯一资金类）→ [AccountType.SAVINGS]，对应 D4「老数据全部转储蓄卡」；
 * - 空值 / 未知值 → [AccountType.SAVINGS]，宁可少分类也不要崩。
 */
fun parseAccountType(raw: String?): AccountType =
    when (raw?.trim()?.uppercase()) {
        "CASH" -> AccountType.CASH
        "SAVINGS" -> AccountType.SAVINGS
        "CREDIT" -> AccountType.CREDIT
        "DEBT" -> AccountType.DEBT
        "INVEST" -> AccountType.INVEST
        else -> AccountType.SAVINGS
    }

/** 预算作用范围。 */
enum class BudgetScope { TOTAL, CATEGORY }

/** 预算周期：按月或按年重算额度。 */
enum class BudgetPeriod { MONTH, YEAR }

/** 周期规则的时间单位。 */
enum class RecurringPeriod { DAY, WEEK, MONTH, YEAR }

/** 借贷方向。 */
enum class DebtDirection {
    /** 借出（对方欠我）。 */
    LEND,

    /** 借入（我欠对方）。 */
    BORROW,
}

/** 收支方向，用于列表/详情页着色。 */
enum class Direction { INCOME, EXPENSE, NEUTRAL }
