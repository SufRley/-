package com.xxjz.ledger.navigation

/** 主 Tab 路由。 */
object Routes {
    const val LEDGER = "ledger"
    const val REPORT = "report"
    const val BUDGET = "budget"
    const val MINE = "mine"

    const val MAIN = "main"

    /** 流水详情 P02。 */
    const val TXN_DETAIL = "txn/{txnId}"
    const val ARG_TXN_ID = "txnId"

    fun txnDetail(id: String): String = "txn/$id"

    /** 模板管理 P05。 */
    const val TEMPLATES = "templates"

    /** 账户列表 P13 / 账户详情 P14。 */
    const val ACCOUNTS = "accounts"
    const val ACCOUNT_DETAIL = "account/{accountId}"
    const val ARG_ACCOUNT_ID = "accountId"

    fun accountDetail(id: String): String = "account/$id"

    /** 搜索 P03 / 日历 P04。 */
    const val SEARCH = "search"
    const val CALENDAR = "calendar"

    /** 分类下钻 P08 / 年度总结 P09。 */
    const val CATEGORY_DRILL = "drill/{categoryId}"
    const val ARG_CATEGORY_ID = "categoryId"
    const val YEAR_SUMMARY = "year_summary"

    fun categoryDrill(id: String): String = "drill/$id"

    /** 预算编辑 P11；[budgetId] 为空表示新建。 */
    const val BUDGET_EDIT = "budget_edit?budgetId={budgetId}&monthKey={monthKey}"
    const val ARG_BUDGET_ID = "budgetId"
    const val ARG_BUDGET_MONTH = "monthKey"

    fun budgetEdit(
        id: String?,
        monthKey: Int,
    ): String = "budget_edit?budgetId=${id.orEmpty()}&monthKey=$monthKey"

    /** 周期记账 P16 / 编辑 P17。 */
    const val RECURRING = "recurring"
    const val RECURRING_EDIT = "recurring_edit?ruleId={ruleId}"
    const val ARG_RULE_ID = "ruleId"

    fun recurringEdit(id: String?): String = "recurring_edit?ruleId=${id.orEmpty()}"

    /** 待报销 P26。 */
    const val REIMBURSEMENT = "reimbursement"

    /** 借贷台账 P18 / 详情 P19。 */
    const val DEBTS = "debts"
    const val DEBT_DETAIL = "debt/{debtId}"
    const val ARG_DEBT_ID = "debtId"

    fun debtDetail(id: String): String = "debt/$id"

    /** M8：备份与恢复 P23、导出 CSV P24、回收站 P22。 */
    const val BACKUP = "backup"
    const val CSV_EXPORT = "csv_export"
    const val TRASH = "trash"

    /** M9：分类/标签/商家管理 P20、设置 P21、首次引导 P00。 */
    const val MANAGE = "manage"
    const val SETTINGS = "settings"
    const val ONBOARDING = "onboarding"
}

/** 底部四个 Tab 的定义，顺序与底部栏一致。 */
enum class MainTab(
    val route: String,
    val label: String,
) {
    LEDGER(Routes.LEDGER, "明细"),
    REPORT(Routes.REPORT, "报表"),
    BUDGET(Routes.BUDGET, "预算"),
    MINE(Routes.MINE, "我的"),
}
