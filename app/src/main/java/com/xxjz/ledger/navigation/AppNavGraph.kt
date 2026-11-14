package com.xxjz.ledger.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.xxjz.ledger.feature.account.AccountDetailScreen
import com.xxjz.ledger.feature.account.AccountListScreen
import com.xxjz.ledger.feature.backup.BackupScreen
import com.xxjz.ledger.feature.budget.BudgetEditScreen
import com.xxjz.ledger.feature.budget.BudgetScreen
import com.xxjz.ledger.feature.calendar.CalendarScreen
import com.xxjz.ledger.feature.category.CategoryManageScreen
import com.xxjz.ledger.feature.detail.TxnDetailScreen
import com.xxjz.ledger.feature.export.CsvExportScreen
import com.xxjz.ledger.feature.ledger.LedgerScreen
import com.xxjz.ledger.feature.mine.MineScreen
import com.xxjz.ledger.feature.onboarding.OnboardingScreen
import com.xxjz.ledger.feature.recurring.RecurringEditScreen
import com.xxjz.ledger.feature.recurring.RecurringListScreen
import com.xxjz.ledger.feature.reimbursement.ReimbursementScreen
import com.xxjz.ledger.feature.report.AssetReportScreen
import com.xxjz.ledger.feature.report.CategoryDrillScreen
import com.xxjz.ledger.feature.report.ReportScreen
import com.xxjz.ledger.feature.report.YearSummaryScreen
import com.xxjz.ledger.feature.search.SearchScreen
import com.xxjz.ledger.feature.settings.SettingsScreen
import com.xxjz.ledger.feature.template.TemplateListScreen
import com.xxjz.ledger.feature.trash.TrashScreen

/**
 * 全部路由的注册表（从 [XxjzApp] 拆出来）。
 *
 * 拆的理由很实际：27 个页面的注册把 `XxjzApp` 撑到 250 行，改一个页面的回调要在几百行里找，
 * 而这里除了「路由 → 页面」的映射没有别的逻辑，单拎出来看页面清单也更清楚。
 *
 * @param onOpenEditor 打开记账面板；(流水 id, 模板 id) 二选一，全 null 表示新建。
 */
internal fun NavGraphBuilder.appNavGraph(
    navController: NavHostController,
    onOpenEditor: (id: String?, templateId: String?) -> Unit,
    onMenuClick: () -> Unit = {},
    onAdd: () -> Unit = {},
) {
    composable(Routes.ONBOARDING) {
        OnboardingScreen(
            onDone = {
                navController.navigate(Routes.LEDGER) {
                    popUpTo(Routes.ONBOARDING) { inclusive = true }
                }
            },
        )
    }
    composable(Routes.LEDGER) {
        LedgerScreen(
            onAdd = { onOpenEditor(null, null) },
            onOpen = { id -> navController.navigate(Routes.txnDetail(id)) },
            onUseTemplate = { id -> onOpenEditor(null, id) },
            onOpenSearch = { navController.navigate(Routes.SEARCH) },
            onOpenCalendar = { navController.navigate(Routes.CALENDAR) },
            onOpenBackup = { navController.navigate(Routes.BACKUP) },
            onOpenBudget = { navController.navigate(Routes.BUDGET) },
            onMenu = onMenuClick,
        )
    }
    composable(
        route = Routes.TXN_DETAIL,
        arguments = listOf(navArgument(Routes.ARG_TXN_ID) { type = NavType.StringType }),
    ) {
        TxnDetailScreen(
            onBack = { navController.popBackStack() },
            onEdit = { id -> onOpenEditor(id, null) },
        )
    }
    composable(Routes.TEMPLATES) {
        TemplateListScreen(
            onBack = { navController.popBackStack() },
            onUse = { id ->
                navController.popBackStack()
                onOpenEditor(null, id)
            },
        )
    }
    composable(Routes.ACCOUNTS) {
        AccountListScreen(
            onBack = { navController.popBackStack() },
            onOpen = { id -> navController.navigate(Routes.accountDetail(id)) },
            onOpenAssetReport = { navController.navigate(Routes.ASSET_REPORT) },
            onOpenReimbursement = { navController.navigate(Routes.REIMBURSEMENT) },
            onMenu = onMenuClick,
            isRootTab = true,
        )
    }
    composable(Routes.ASSET_REPORT) {
        AssetReportScreen(
            onBack = { navController.popBackStack() },
            onOpenTxn = { id -> navController.navigate(Routes.txnDetail(id)) },
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
    composable(Routes.REPORT) {
        ReportScreen(
            onDrill = { id -> navController.navigate(Routes.categoryDrill(id)) },
            onYearSummary = { navController.navigate(Routes.YEAR_SUMMARY) },
            onMenu = onMenuClick,
        )
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
            onOpenAccounts = { navController.navigate(Routes.ACCOUNTS) },
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
