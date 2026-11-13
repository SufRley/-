package com.xxjz.ledger.navigation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.xxjz.ledger.core.common.ShortcutAction
import com.xxjz.ledger.core.common.StartDestination
import com.xxjz.ledger.core.common.readOnboardingDone
import com.xxjz.ledger.data.datastore.SettingsRepository
import com.xxjz.ledger.feature.account.AccountDetailScreen
import com.xxjz.ledger.feature.account.AccountListScreen
import com.xxjz.ledger.feature.backup.BackupScreen
import com.xxjz.ledger.feature.budget.BudgetEditScreen
import com.xxjz.ledger.feature.budget.BudgetScreen
import com.xxjz.ledger.feature.calendar.CalendarScreen
import com.xxjz.ledger.feature.category.CategoryManageScreen
import com.xxjz.ledger.feature.debt.DebtDetailScreen
import com.xxjz.ledger.feature.debt.DebtListScreen
import com.xxjz.ledger.feature.detail.TxnDetailScreen
import com.xxjz.ledger.feature.editor.EditorSheet
import com.xxjz.ledger.feature.editor.EditorViewModel
import com.xxjz.ledger.feature.export.CsvExportScreen
import com.xxjz.ledger.feature.ledger.LedgerScreen
import com.xxjz.ledger.feature.mine.MineScreen
import com.xxjz.ledger.feature.onboarding.OnboardingScreen
import com.xxjz.ledger.feature.recurring.RecurringEditScreen
import com.xxjz.ledger.feature.recurring.RecurringListScreen
import com.xxjz.ledger.feature.reimbursement.ReimbursementScreen
import com.xxjz.ledger.feature.report.CategoryDrillScreen
import com.xxjz.ledger.feature.report.ReportScreen
import com.xxjz.ledger.feature.report.YearSummaryScreen
import com.xxjz.ledger.feature.search.SearchScreen
import com.xxjz.ledger.feature.settings.SettingsScreen
import com.xxjz.ledger.feature.template.TemplateListScreen
import com.xxjz.ledger.feature.trash.TrashScreen

/**
 * 应用根组件：主框架 + NavHost + 全局记账面板。
 *
 * - Tab 切换走 NavController，保证返回栈与状态恢复正确；
 * - 记账面板挂在根节点，任意页面点「＋」都能拉起，每次打开换 key 拿到干净的新 ViewModel；
 * - 详情页隐藏底部导航，给内容留出整屏。
 */
@Composable
fun XxjzApp(
    navController: NavHostController = rememberNavController(),
    onAdd: () -> Unit = {},
    routeOverride: String? = null,
    onRouteChange: ((String) -> Unit)? = null,
    /** 桌面快捷方式落点（AC-84）；为 null 表示普通启动。 */
    shortcut: ShortcutAction? = null,
    onShortcutConsumed: () -> Unit = {},
    settings: SettingsRepository,
) {
    var editorOpen by remember { mutableStateOf(false) }
    var editingId by remember { mutableStateOf<String?>(null) }
    var templateId by remember { mutableStateOf<String?>(null) }
    var editorKey by remember { mutableIntStateOf(0) }

    // 首屏落点：null 表示还在读引导标记（几十毫秒的磁盘读），读完再建 NavHost，
    // 避免先闪一下明细页再跳到引导（那样返回栈里会多一层，用户按返回会很怪）。
    var startDestination by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        if (startDestination == null) {
            startDestination = StartDestination.decide(readOnboardingDone(settings), shortcut)
        }
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val route = routeOverride ?: backStackEntry?.destination?.route
    val isDetail =
        startDestination == Routes.ONBOARDING && route == Routes.ONBOARDING ||
            route == Routes.SEARCH ||
            route == Routes.CALENDAR ||
            route?.startsWith("txn/") == true ||
            route?.startsWith("account/") == true ||
            route == Routes.ACCOUNTS ||
            route == Routes.TEMPLATES ||
            route == Routes.YEAR_SUMMARY ||
            route == Routes.BUDGET_EDIT ||
            route == Routes.RECURRING ||
            route == Routes.RECURRING_EDIT ||
            route == Routes.REIMBURSEMENT ||
            route == Routes.DEBTS ||
            route == Routes.BACKUP ||
            route == Routes.CSV_EXPORT ||
            route == Routes.TRASH ||
            route == Routes.MANAGE ||
            route == Routes.SETTINGS ||
            route?.startsWith("debt/") == true ||
            route?.startsWith("drill/") == true

    fun openEditor(
        id: String? = null,
        template: String? = null,
    ) {
        editingId = id
        templateId = template
        editorKey += 1
        editorOpen = true
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        // 快捷方式只在挂载时消费一次：`shortcut` 被置空后就什么都不做，
        // 不会因为重组反复弹面板或反复跳页。
        LaunchedEffect(shortcut) {
            val action = shortcut ?: return@LaunchedEffect
            when (action) {
                ShortcutAction.NEW_EXPENSE -> openEditor()
                ShortcutAction.OPEN_REPORT -> navController.navigate(Routes.REPORT)
                ShortcutAction.OPEN_SEARCH -> navController.navigate(Routes.SEARCH)
            }
            onShortcutConsumed()
        }
        MainScreen(
            currentRoute = route,
            showBottomBar = !isDetail,
            onSelectTab = { tab ->
                onRouteChange?.invoke(tab.route)
                navController.navigate(tab.route) {
                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
            },
            onAddClick = {
                onAdd()
                openEditor()
            },
        ) {
            val start = startDestination
            if (start == null) {
                // 还在读引导标记：留白，别先渲染主界面（理由见上面 startDestination 的注释）
                return@MainScreen
            }
            NavHost(
                navController = navController,
                startDestination = start,
                modifier = Modifier.fillMaxSize(),
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
                        onAdd = { openEditor() },
                        onOpen = { id -> navController.navigate(Routes.txnDetail(id)) },
                        onUseTemplate = { id -> openEditor(template = id) },
                        onOpenSearch = { navController.navigate(Routes.SEARCH) },
                        onOpenCalendar = { navController.navigate(Routes.CALENDAR) },
                        onOpenBackup = { navController.navigate(Routes.BACKUP) },
                    )
                }
                composable(
                    route = Routes.TXN_DETAIL,
                    arguments = listOf(navArgument(Routes.ARG_TXN_ID) { type = NavType.StringType }),
                ) {
                    TxnDetailScreen(
                        onBack = { navController.popBackStack() },
                        onEdit = { id -> openEditor(id) },
                    )
                }
                composable(Routes.TEMPLATES) {
                    TemplateListScreen(
                        onBack = { navController.popBackStack() },
                        onUse = { id ->
                            navController.popBackStack()
                            openEditor(template = id)
                        },
                    )
                }
                composable(Routes.ACCOUNTS) {
                    AccountListScreen(
                        onBack = { navController.popBackStack() },
                        onOpen = { id -> navController.navigate(Routes.accountDetail(id)) },
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
                composable(Routes.DEBTS) {
                    DebtListScreen(
                        onBack = { navController.popBackStack() },
                        onOpenDetail = { id -> navController.navigate(Routes.debtDetail(id)) },
                    )
                }
                composable(
                    route = Routes.DEBT_DETAIL,
                    arguments = listOf(navArgument(Routes.ARG_DEBT_ID) { type = NavType.StringType }),
                ) {
                    DebtDetailScreen(onBack = { navController.popBackStack() })
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
                        onOpenDebts = { navController.navigate(Routes.DEBTS) },
                        onOpenBackup = { navController.navigate(Routes.BACKUP) },
                        onOpenCsvExport = { navController.navigate(Routes.CSV_EXPORT) },
                        onOpenTrash = { navController.navigate(Routes.TRASH) },
                        onOpenManage = { navController.navigate(Routes.MANAGE) },
                        onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                    )
                }
            }
        }

        if (editorOpen) {
            val editorViewModel: EditorViewModel = hiltViewModel(key = "editor-$editorKey")
            EditorSheet(
                viewModel = editorViewModel,
                editTxnId = editingId,
                templateId = templateId,
                onDismiss = { editorOpen = false },
                onSaved = { editorOpen = false },
            )
        }
    }
}
