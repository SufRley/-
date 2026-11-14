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

    /** v1.8.0：资产报表（资产构成 / 负债构成 / 净资产趋势 / 资产变化记录）。 */
    const val ASSET_REPORT = "asset_report"

    /** M9：分类/标签/商家管理 P20、设置 P21、首次引导 P00。 */
    const val MANAGE = "manage"
    const val SETTINGS = "settings"
    const val ONBOARDING = "onboarding"
}

/**
 * 底部三个 Tab（v1.8.0 / PRD D8）：明细 / 报表 / 账户。
 *
 * 「预算」下沉为明细页底部的概览卡，「我的」取消、内容并入左上角 ☰ 抽屉；
 * 两者的独立路由仍然保留，只是不再占据 Tab 位。
 */
enum class MainTab(
    val route: String,
    val label: String,
) {
    LEDGER(Routes.LEDGER, "明细"),
    REPORT(Routes.REPORT, "报表"),
    ACCOUNTS(Routes.ACCOUNTS, "账户"),
}

/** 抽屉里的七项（PRD D11），顺序即展示顺序。 */
enum class DrawerEntry(
    val route: String,
    val label: String,
) {
    TEMPLATES(Routes.TEMPLATES, "模板"),
    RECURRING(Routes.RECURRING, "周期记账"),
    BACKUP(Routes.BACKUP, "备份与恢复"),
    CSV_EXPORT(Routes.CSV_EXPORT, "CSV 导出"),
    TRASH(Routes.TRASH, "回收站"),
    MANAGE(Routes.MANAGE, "分类管理"),
    SETTINGS(Routes.SETTINGS, "设置"),
}

/** 主 Tab 页的路由集合：只有这些页面显示底部栏、左上角 ☰ 与悬浮「＋」。 */
val MAIN_TAB_ROUTES: Set<String> = MainTab.entries.map { it.route }.toSet()
