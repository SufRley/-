package com.xxjz.ledger.navigation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.xxjz.ledger.feature.account.AccountListScreen
import com.xxjz.ledger.feature.ledger.LedgerNav
import com.xxjz.ledger.feature.ledger.LedgerScreen
import com.xxjz.ledger.feature.report.ReportScreen

/**
 * 三个主 Tab 的选中态与「切回去」的历史（v1.9.5 第 2 条）。
 *
 * 为什么不是简单的 `mutableStateOf(MainTab)`：横滑切页之后按返回，用户期望回到上一页而不是
 * 直接退出应用（AC-2.5）。以前每个 Tab 是一个导航目的地，这件事由 NavController 的返回栈
 * 顺手办掉；现在 Tab 只是 pager 的下标，导航栈帮不上忙，于是把「换页」自己的历史记下来。
 *
 * 做成普通类而不是 Composable 里的几个 `remember`：push / pop / 去重能被单测直接钉住，
 * 不用起 Robolectric 去点界面。内部用 Snapshot 列表，读数在 Compose 里能触发重组；
 * 在纯 JVM 单测里照样能读写，不需要启动 Compose 运行时。
 */
@Stable
class TabController(initial: MainTab = MainTab.LEDGER) {
    /** 当前停留的 Tab。 */
    var current by mutableStateOf(initial)
        private set

    private val history = mutableStateListOf<MainTab>()

    /** 还有没有上一页可回：返回键据此决定是「切回去」还是「交给系统退出」。 */
    val canGoBack: Boolean get() = history.isNotEmpty()

    /**
     * 切到 [tab]。
     *
     * 与当前相同时什么都不做：横滑回弹、连点同一个底栏项、外部重复下发同一个目标，
     * 都会走到这里，若照单全收，历史栈会被同一个 Tab 塞满，返回键要按好几次才有反应。
     */
    fun select(tab: MainTab) {
        if (tab == current) return
        // 历史最多留 [MAX_DEPTH] 层：来回横滑十几次之后，用户不该被判「按十几次返回才退出」。
        // 三个 Tab 之间真正需要回退的路径本来也超不过两层。
        if (history.size >= MAX_DEPTH) history.removeAt(0)
        history.add(current)
        current = tab
    }

    /** 回到上一页；没有历史时返回 false，让调用方把返回键交还给系统。 */
    fun back(): Boolean {
        val previous = history.removeLastOrNull() ?: return false
        current = previous
        return true
    }

    private companion object {
        /** 历史深度上限。 */
        const val MAX_DEPTH = 3
    }
}

/**
 * 三 Tab 横滑容器。
 *
 * 双向同步，但两侧的触发时机刻意不同：
 * - 手指滑完（`settledPage` 落定）→ 通知 [onSelect]，底栏跟着高亮；
 * - 外部改选中态（点底栏、桌面快捷方式、从「我的」进账户）→ 动画滚到对应页。
 *
 * 回传用 `settledPage` 而不是 `currentPage`：后者在滑动途中会先跳到目标页，中途路过的
 * 那一页会被记成一次选中，历史栈里就多出一次根本没停留过的切换，返回键按一下就"卡住"。
 *
 * 关于与页内横向滚动的冲突（AC-2.4，例如明细页的模板条）：Compose 的拖动手势在**按下时**
 * 就由最深的那一层认领，页内的横向列表比这里的 pager 更深，所以在模板条上起手的横滑
 * 只会滚内容、不会切页——PRD 里「48dp 且大于纵向 1.5 倍」的判据因此不需要自己再写一遍。
 */
@Composable
internal fun MainTabsPager(
    currentTab: MainTab,
    onSelect: (MainTab) -> Unit,
    modifier: Modifier = Modifier,
    pageContent: @Composable (MainTab) -> Unit,
) {
    val pagerState = rememberPagerState(initialPage = currentTab.ordinal) { MainTab.entries.size }
    // 落定回调只在 LaunchedEffect 里持有一次，用 rememberUpdatedState 保证读到的是最新值，
    // 不会因为组合时机不同而通知到一个过期的目标
    val latestOnSelect by rememberUpdatedState(onSelect)
    val latestTab by rememberUpdatedState(currentTab)

    LaunchedEffect(pagerState.settledPage) {
        val settled = MainTab.ofIndex(pagerState.settledPage)
        if (settled != latestTab) latestOnSelect(settled)
    }
    LaunchedEffect(currentTab) {
        if (pagerState.currentPage != currentTab.ordinal) {
            pagerState.animateScrollToPage(currentTab.ordinal)
        }
    }

    HorizontalPager(
        state = pagerState,
        modifier = modifier.fillMaxSize().testTag("main_pager"),
    ) { index ->
        pageContent(MainTab.ofIndex(index))
    }
}

/**
 * 主框架目的地注册：三个 Tab 都住在 [Routes.MAIN] 这一个目的地里。
 *
 * 三个页面各带自己的 ViewModel，但都挂在同一个 `NavBackStackEntry` 上，所以来回滑页
 * 不会重建 ViewModel、不会重新查库，也就没有「滑过去先空一帧」的闪烁。
 */
internal fun NavGraphBuilder.mainTabsRoute(
    navController: NavHostController,
    tabs: TabController,
    onMenuClick: () -> Unit,
) {
    composable(Routes.MAIN) {
        MainTabsPager(currentTab = tabs.current, onSelect = tabs::select) { tab ->
            when (tab) {
                MainTab.LEDGER -> LedgerScreen(nav = ledgerNav(navController, onMenuClick))
                MainTab.REPORT ->
                    ReportScreen(
                        onDrill = { id -> navController.navigate(Routes.categoryDrill(id)) },
                        onYearSummary = { navController.navigate(Routes.YEAR_SUMMARY) },
                        onMenu = onMenuClick,
                    )
                MainTab.ACCOUNTS ->
                    AccountListScreen(
                        // 主 Tab 上左上角是 ☰，没有返回箭头可点
                        onBack = {},
                        onOpen = { id -> navController.navigate(Routes.accountDetail(id)) },
                        onOpenAssetReport = { navController.navigate(Routes.ASSET_REPORT) },
                        onOpenReimbursement = { navController.navigate(Routes.REIMBURSEMENT) },
                        onMenu = onMenuClick,
                        isRootTab = true,
                    )
            }
        }
    }
}

/** 明细页的回调打包。单独拎出来只是为了让上面那个 `when` 读起来还是一屏能看完。 */
private fun ledgerNav(
    navController: NavHostController,
    onMenuClick: () -> Unit,
): LedgerNav =
    LedgerNav(
        onAdd = { navController.navigate(Routes.editor()) },
        onOpenTxn = { id -> navController.navigate(Routes.txnDetail(id)) },
        onEditTxn = { id -> navController.navigate(Routes.editor(txnId = id)) },
        onOpenAccount = { id -> navController.navigate(Routes.accountDetail(id)) },
        onOpenRecurring = { navController.navigate(Routes.RECURRING) },
        onOpenSplit = { id -> navController.navigate(Routes.split(id)) },
        onUseTemplate = { id -> navController.navigate(Routes.editor(templateId = id)) },
        onOpenSearch = { navController.navigate(Routes.SEARCH) },
        onOpenCalendar = { navController.navigate(Routes.CALENDAR) },
        onOpenBudget = { navController.navigate(Routes.BUDGET) },
        onMenu = onMenuClick,
        onOpenReimbursement = { navController.navigate(Routes.REIMBURSEMENT) },
        onOpenBackup = { navController.navigate(Routes.BACKUP) },
    )
