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
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.xxjz.ledger.core.common.ShortcutAction
import com.xxjz.ledger.core.common.StartDestination
import com.xxjz.ledger.core.common.readOnboardingDone
import com.xxjz.ledger.data.datastore.SettingsRepository
import com.xxjz.ledger.feature.editor.EditorSheet
import com.xxjz.ledger.feature.editor.EditorViewModel

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
    // 主 Tab 页以外的都算二级页：二级页隐藏底栏、左上角是返回箭头（PRD D10）
    val isDetail =
        !MainChrome.showBottomBar(route) ||
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
        var drawerOpen by remember { mutableStateOf(false) }
        XxjzDrawer(
            open = drawerOpen,
            onClose = { drawerOpen = false },
            onOpenRoute = { target ->
                onRouteChange?.invoke(target)
                navController.navigate(target) { launchSingleTop = true }
            },
        ) {
            MainScreen(
                currentRoute = route,
                showBottomBar = !isDetail,
                onSelectTab = { tab ->
                    onRouteChange?.invoke(tab.route)
                    drawerOpen = false
                    navController.navigate(tab.route) {
                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
            ) {
                val start = startDestination
                if (start == null) {
                    // 还在读引导标记：留白，别先渲染主界面（理由见上面 startDestination 的注释）
                    return@MainScreen
                }
                // 抽屉只能从主 Tab 页打开：二级页不显示 ☰（PRD D10）
                LaunchedEffect(route) {
                    if (!MainChrome.showDrawer(route)) drawerOpen = false
                }
                NavHost(
                    navController = navController,
                    startDestination = start,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    appNavGraph(
                        navController = navController,
                        onOpenEditor = { id, template -> openEditor(id, template) },
                        onMenuClick = { drawerOpen = true },
                        onAdd = {
                            onAdd()
                            openEditor()
                        },
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
