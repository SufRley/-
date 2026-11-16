package com.xxjz.ledger.navigation

import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.xxjz.ledger.feature.account.AccountDetailScreen
import com.xxjz.ledger.feature.backup.BackupScreen
import com.xxjz.ledger.feature.budget.BudgetEditScreen
import com.xxjz.ledger.feature.budget.BudgetScreen
import com.xxjz.ledger.feature.calendar.CalendarScreen
import com.xxjz.ledger.feature.category.CategoryManageScreen
import com.xxjz.ledger.feature.detail.TxnDetailScreen
import com.xxjz.ledger.feature.editor.EditorScreen
import com.xxjz.ledger.feature.editor.EditorViewModel
import com.xxjz.ledger.feature.export.CsvExportScreen
import com.xxjz.ledger.feature.mine.MineScreen
import com.xxjz.ledger.feature.onboarding.OnboardingScreen
import com.xxjz.ledger.feature.recurring.RecurringEditScreen
import com.xxjz.ledger.feature.recurring.RecurringListScreen
import com.xxjz.ledger.feature.reimbursement.ReimbursementScreen
import com.xxjz.ledger.feature.report.AssetReportScreen
import com.xxjz.ledger.feature.report.CategoryDrillScreen
import com.xxjz.ledger.feature.report.YearSummaryScreen
import com.xxjz.ledger.feature.search.SearchScreen
import com.xxjz.ledger.feature.settings.SettingsScreen
import com.xxjz.ledger.feature.split.SplitTxnScreen
import com.xxjz.ledger.feature.template.TemplateListScreen
import com.xxjz.ledger.feature.trash.TrashScreen

/**
 * 全部路由的注册表（从 [XxjzApp] 拆出来）。
 *
 * 拆的理由很实际：27 个页面的注册把 `XxjzApp` 撑到 250 行，改一个页面的回调要在几百行里找，
 * 而这里除了「路由 → 页面」的映射没有别的逻辑，单拎出来看页面清单也更清楚。
 *
 * 记账页 v1.9.1 起是全屏路由（[Routes.EDITOR]），不再需要外部注入「打开面板」的回调，
 * 各页面直接 navigate 过去即可。
 */
internal fun NavGraphBuilder.appNavGraph(
    navController: NavHostController,
    tabs: TabController,
    onOpenTab: (MainTab) -> Unit,
    onMenuClick: () -> Unit = {},
    onAdd: () -> Unit = {},
) {
    composable(Routes.ONBOARDING) {
        OnboardingScreen(
            onDone = {
                navController.navigate(Routes.MAIN) {
                    popUpTo(Routes.ONBOARDING) { inclusive = true }
                }
            },
        )
    }
    composable(
        route = Routes.EDITOR,
        arguments =
            listOf(
                navArgument(Routes.ARG_TXN_ID) {
                    type = NavType.StringType
                    defaultValue = ""
                },
                navArgument(Routes.ARG_TEMPLATE_ID) {
                    type = NavType.StringType
                    defaultValue = ""
                },
            ),
    ) { entry ->
        val viewModel: EditorViewModel = hiltViewModel()
        EditorScreen(
            viewModel = viewModel,
            editTxnId = entry.arguments?.getString(Routes.ARG_TXN_ID)?.takeIf { it.isNotEmpty() },
            templateId = entry.arguments?.getString(Routes.ARG_TEMPLATE_ID)?.takeIf { it.isNotEmpty() },
            onDismiss = { navController.popBackStack() },
            onSaved = { navController.popBackStack() },
            onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            onOpenTemplates = { navController.navigate(Routes.TEMPLATES) },
        )
    }
    // 明细 / 报表 / 账户三页合住在 Routes.MAIN，由 MainTabsPager 承载（v1.9.5 第 2 条）
    mainTabsRoute(navController = navController, tabs = tabs, onMenuClick = onMenuClick)
    composable(
        route = Routes.TXN_DETAIL,
        arguments = listOf(navArgument(Routes.ARG_TXN_ID) { type = NavType.StringType }),
    ) {
        TxnDetailScreen(
            onBack = { navController.popBackStack() },
            onEdit = { id -> navController.navigate(Routes.editor(txnId = id)) },
        )
    }
    splitRoute(navController)
    composable(Routes.TEMPLATES) {
        TemplateListScreen(
            onBack = { navController.popBackStack() },
            onUse = { id -> navController.navigate(Routes.editor(templateId = id)) },
        )
    }
    composable(Routes.ASSET_REPORT) {
        AssetReportScreen(
            onBack = { navController.popBackStack() },
            onOpenTxn = { id -> navController.navigate(Routes.txnDetail(id)) },
            onOpenSettings = { navController.navigate(Routes.SETTINGS) },
        )
    }
    composable(
        route = Routes.ACCOUNT_DETAIL,
        arguments = listOf(navArgument(Routes.ARG_ACCOUNT_ID) { type = NavType.StringType }),
    ) {
        AccountDetailScreen(
            onBack = { navController.popBackStack() },
            onOpenTxn = { id -> navController.navigate(Routes.txnDetail(id)) },
        )
    }
    composable(Routes.SEARCH) {
        SearchScreen(
            onBack = { navController.popBackStack() },
            onOpen = { id -> navController.navigate(Routes.txnDetail(id)) },
        )
    }
    composable(Routes.CALENDAR) {
        CalendarScreen(onBack = { navController.popBackStack() })
    }
    composable(
        route = Routes.CATEGORY_DRILL,
        arguments = listOf(navArgument(Routes.ARG_CATEGORY_ID) { type = NavType.StringType }),
    ) { entry ->
        CategoryDrillScreen(
            categoryId = entry.arguments?.getString(Routes.ARG_CATEGORY_ID).orEmpty(),
            onBack = { navController.popBackStack() },
            onOpenTxn = { id -> navController.navigate(Routes.txnDetail(id)) },
        )
    }
    composable(Routes.YEAR_SUMMARY) {
        YearSummaryScreen(onBack = { navController.popBackStack() })
    }
    composable(Routes.BUDGET) {
        BudgetScreen(
            onEdit = { id, monthKey -> navController.navigate(Routes.budgetEdit(id, monthKey)) },
        )
    }
    composable(
        route = Routes.BUDGET_EDIT,
        arguments =
            listOf(
                navArgument(Routes.ARG_BUDGET_ID) {
                    type = NavType.StringType
                    defaultValue = ""
                },
                navArgument(Routes.ARG_BUDGET_MONTH) {
                    type = NavType.IntType
                    defaultValue = 0
                },
            ),
    ) {
        BudgetEditScreen(onBack = { navController.popBackStack() })
    }
    composable(Routes.RECURRING) {
        RecurringListScreen(
            onBack = { navController.popBackStack() },
            onEdit = { id -> navController.navigate(Routes.recurringEdit(id)) },
        )
    }
    composable(
        route = Routes.RECURRING_EDIT,
        arguments =
            listOf(
                navArgument(Routes.ARG_RULE_ID) {
                    type = NavType.StringType
                    defaultValue = ""
                },
            ),
    ) {
        RecurringEditScreen(onBack = { navController.popBackStack() })
    }
    composable(Routes.REIMBURSEMENT) {
        ReimbursementScreen(onBack = { navController.popBackStack() })
    }
    composable(Routes.BACKUP) {
        BackupScreen(onBack = { navController.popBackStack() })
    }
    composable(Routes.CSV_EXPORT) {
        CsvExportScreen(onBack = { navController.popBackStack() })
    }
    composable(Routes.TRASH) {
        TrashScreen(onBack = { navController.popBackStack() })
    }
    composable(Routes.MANAGE) {
        CategoryManageScreen(onBack = { navController.popBackStack() })
    }
    composable(Routes.SETTINGS) {
        SettingsScreen(onBack = { navController.popBackStack() })
    }
    composable(Routes.MINE) {
        MineScreen(
            onOpenAccounts = { onOpenTab(MainTab.ACCOUNTS) },
            onOpenTemplates = { navController.navigate(Routes.TEMPLATES) },
            onOpenRecurring = { navController.navigate(Routes.RECURRING) },
            onOpenReimbursement = { navController.navigate(Routes.REIMBURSEMENT) },
            onOpenBackup = { navController.navigate(Routes.BACKUP) },
            onOpenCsvExport = { navController.navigate(Routes.CSV_EXPORT) },
            onOpenTrash = { navController.navigate(Routes.TRASH) },
            onOpenManage = { navController.navigate(Routes.MANAGE) },
            onOpenSettings = { navController.navigate(Routes.SETTINGS) },
        )
    }
}

/**
 * 拆分明细页单独注册（v1.9.5 第 8 条）。
 *
 * 从 [appNavGraph] 里拎出来纯粹是为了压住 detekt 的 `LongMethod`：
 * 那个函数已经两百行，每加一个页面的注册都要重新数一遍行数。
 */
private fun NavGraphBuilder.splitRoute(navController: NavHostController) {
    composable(
        route = Routes.SPLIT,
        arguments = listOf(navArgument(Routes.ARG_TXN_ID) { type = NavType.StringType }),
    ) {
        SplitTxnScreen(
            onBack = { navController.popBackStack() },
            // 拆完回明细页：用户接下来要看的就是那几笔新账有没有对上
            onDone = { navController.popBackStack() },
        )
    }
}
