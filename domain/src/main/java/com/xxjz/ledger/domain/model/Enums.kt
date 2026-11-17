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

/**
 * 借贷动作（v1.9.0 / PRD 2.2）。
 *
 * 四种操作 + 一种核销，全部落成 `type = DEBT` 的流水；`amount` 恒为正，
 * 方向由枚举本身表达，由余额聚合 SQL 翻译成加减号（见 `AccountDao.observeBalances`）。
 *
 * | 动作 | 借贷账户 | 资金账户 | 净资产 |
 * |---|---|---|---|
 * | 借出 | +   | −   | 不变 |
 * | 收款 | −   | +   | 不变 |
 * | 借入 | −   | +   | 不变 |
 * | 还款 | +   | −   | 不变 |
 * | 坏账核销 | − | 无 | 减少 |
 */
enum class DebtAction {
    /** 借出：钱从资金账户流向对方。 */
    LEND_OUT,

    /** 收款：对方把钱还回来。 */
    COLLECT,

    /** 借入：钱从对方流到我的资金账户。 */
    BORROW_IN,

    /** 还款：我把钱还给对方。 */
    REPAY,

    /** 坏账核销：确认收不回来，把待收余额清零。不会让净资产增加，故只允许「我借出」方向。 */
    WRITE_OFF,
}

/**
 * 借贷动作容错解析：v1.9.0 之前的历史 `DEBT` 流水没有这一列（null）。
 *
 * 返回 null 表示「这条流水不改变任何账户余额」，余额聚合 SQL 用
 * `IN ('LEND_OUT','REPAY')` 白名单匹配，未知值天然落空，不会误加。
 */
fun parseDebtAction(raw: String?): DebtAction? =
    when (raw?.trim()?.uppercase()) {
        "LEND_OUT" -> DebtAction.LEND_OUT
        "COLLECT" -> DebtAction.COLLECT
        "BORROW_IN" -> DebtAction.BORROW_IN
        "REPAY" -> DebtAction.REPAY
        "WRITE_OFF" -> DebtAction.WRITE_OFF
        else -> null
    }

/** 收支方向，用于列表/详情页着色。 */
enum class Direction { INCOME, EXPENSE, NEUTRAL }
