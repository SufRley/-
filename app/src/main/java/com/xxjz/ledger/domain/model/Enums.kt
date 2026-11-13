package com.xxjz.ledger.domain.model

/** 记账类型。 */
enum class TxnType { EXPENSE, INCOME, TRANSFER, DEBT }

/** 账户类型：资金 / 信用 / 借贷。AC-16 的三分组即来源于此。 */
enum class AccountType {
    /** 现金、储蓄卡、电子钱包等。 */
    FUND,

    /** 信用卡、花呗等信用类，余额为负表示欠款。 */
    CREDIT,

    /** 借出 / 借入类账户。 */
    DEBT,
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
