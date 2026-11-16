package com.xxjz.ledger.navigation

/** 主 Tab 路由。 */
object Routes {
    /**
     * 主框架：三个 Tab 合住的唯一目的地（v1.9.5 第 2 条）。
     *
     * 以前明细 / 报表 / 账户是三个独立路由，横滑切页只能靠 `navigate` 实现。那条路走不通：
     * 每次换页都会压一个新目的地、重建一次 `NavBackStackEntry`，连带把 ViewModel 也重建，
     * 滑过去先闪一下空页再填数据——比不能滑更难受。合并成一个目的地之后，Tab 只是 pager 的
     * 页下标，切页不碰导航栈，三个 ViewModel 也都留在同一个 entry 里不重建。
     */
    const val MAIN = "main"

    const val BUDGET = "budget"
    const val MINE = "mine"

    /** 流水详情 P02。 */
    const val TXN_DETAIL = "txn/{txnId}"
    const val ARG_TXN_ID = "txnId"

    fun txnDetail(id: String): String = "txn/$id"

    /**
     * 全屏记账页（v1.9.1 第 5 条）。
     *
     * 以前是挂在根节点的 ModalBottomSheet，用户明确说「不要抽屉，点加号后是另一个页面」，
     * 于是改成正经路由。两个可选参数：传 [ARG_TXN_ID] 是编辑，传 [ARG_TEMPLATE_ID] 是套模板。
     */
    const val EDITOR = "editor?txnId={txnId}&templateId={templateId}"
    const val ARG_TEMPLATE_ID = "templateId"

    fun editor(
        txnId: String? = null,
        templateId: String? = null,
    ): String = "editor?txnId=${txnId.orEmpty()}&templateId=${templateId.orEmpty()}"

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

    /** 拆分明细 P27（v1.9.5 第 8 条）：一笔账拆成多个账户各自出多少。 */
    const val SPLIT = "split/{txnId}"

    fun split(txnId: String): String = "split/$txnId"

    // 借贷台账（v1.9.0 起取消）：借贷改为「一人一账户」，直接从账户列表进入详情，
    // 不再有独立的台账页与详情页（PRD 2.5）。

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
 *
 * v1.9.5 起 [key] 不再是导航目的地（三页合住在 [Routes.MAIN]），而是 Tab 的稳定标识：
 * 底栏高亮、testTag、横滑下标换算都以它为准。之所以不改名成 `id`，是因为它出现在
 * 保存态与 testTag 里，改名要连带改动一串地方，收益只有「名字更准」这一点。
 */
enum class MainTab(
    val key: String,
    val label: String,
) {
    LEDGER("ledger", "明细"),
    REPORT("report", "报表"),
    ACCOUNTS("accounts", "账户"),
    ;

    companion object {
        /** 页下标 → Tab；越界时夹回末页，宁可停在某一页也不要崩。 */
        fun ofIndex(index: Int): MainTab = entries[index.coerceIn(0, entries.lastIndex)]

        /** 保存态 / 路由参数 → Tab；认不出来返回 null，由调用方决定兜底到哪一页。 */
        fun ofKey(key: String?): MainTab? = entries.firstOrNull { it.key == key }
    }
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
val MAIN_TAB_ROUTES: Set<String> = setOf(Routes.MAIN)
