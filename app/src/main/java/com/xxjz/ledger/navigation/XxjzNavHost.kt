package com.xxjz.ledger.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.xxjz.ledger.core.common.ShortcutAction
import com.xxjz.ledger.core.common.StartDestination
import com.xxjz.ledger.domain.model.readOnboardingDone
import com.xxjz.ledger.domain.repository.SettingsRepository

/**
 * 应用根组件：主框架 + NavHost。
 *
 * - Tab 切换不再走 NavController（v1.9.5 第 2 条）：三个 Tab 是同一个目的地里的三页，
 *   由 [TabController] 记选中态，横滑与点底栏改的都是它；
 * - 记账页 v1.9.1 起是全屏路由（用户明确要求「不要抽屉」），不再挂在根节点；
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
    // 首屏落点：null 表示还在读引导标记（几十毫秒的磁盘读），读完再建 NavHost，
    // 避免先闪一下明细页再跳到引导（那样返回栈里会多一层，用户按返回会很怪）。
    var startDestination by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        if (startDestination == null) {
            startDestination = StartDestination.decide(readOnboardingDone(settings), shortcut)
        }
    }

    // 停在哪个 Tab 要跨进程重启记住：用户上次看的是报表，重进不该被扔回明细。
    // 只存 key 而不是枚举：rememberSaveable 的默认 Saver 认得 String。
    var savedTabKey by rememberSaveable { mutableStateOf(MainTab.LEDGER.key) }
    val tabs = remember { TabController(MainTab.ofKey(savedTabKey) ?: MainTab.LEDGER) }
    LaunchedEffect(tabs.current) { savedTabKey = tabs.current.key }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val route = routeOverride ?: backStackEntry?.destination?.route
    // 主 Tab 页以外的都算二级页：二级页隐藏底栏、左上角是返回箭头（PRD D10）
    // 判定收在 MainChrome 里，避免此处各写一套后再把主 Tab 误判成二级页
    val isDetail = MainChrome.isDetail(route)

    /**
     * 切 Tab 的唯一入口：先改选中态，必要时再落到主框架目的地。
     *
     * 目的地要按「调用当刻」的现状判，不能用组合时捕获的 route——从抽屉里的「我的」点
     * 「账户」时 route 还是 `mine`，必须补一次导航；而在主框架里点底栏时已经在那儿了，
     * 再 navigate 一次（哪怕 launchSingleTop）会把这一层目的地重建，pager 位置和
     * 三个 ViewModel 全丢，滑过去会闪一下。
     */
    fun openTab(tab: MainTab) {
        tabs.select(tab)
        onRouteChange?.invoke(Routes.MAIN)
        if (navController.currentDestination?.route != Routes.MAIN) {
            navController.navigate(Routes.MAIN) { launchSingleTop = true }
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        // 快捷方式只在挂载时消费一次：`shortcut` 被置空后就什么都不做，
        // 不会因为重组反复弹面板或反复跳页。
        LaunchedEffect(shortcut) {
            val action = shortcut ?: return@LaunchedEffect
            when (action) {
                ShortcutAction.NEW_EXPENSE -> navController.navigate(Routes.editor())
                ShortcutAction.OPEN_REPORT -> openTab(MainTab.REPORT)
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
            // AC-2.5：横滑切过页之后按返回，先回到切页前的那一页，而不是直接退出应用。
            // 只在主框架页上接管；二级页的返回仍旧交给导航栈。
            if (route == Routes.MAIN) {
                BackHandler(enabled = tabs.canGoBack) { tabs.back() }
            }
            MainScreen(
                currentTab = tabs.current,
                showBottomBar = !isDetail,
                onSelectTab = ::openTab,
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
                        tabs = tabs,
                        onOpenTab = ::openTab,
                        onMenuClick = { drawerOpen = true },
                        onAdd = onAdd,
                    )
                }
            }
        }
    }
}
