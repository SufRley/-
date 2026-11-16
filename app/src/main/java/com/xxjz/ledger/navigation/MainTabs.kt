package com.xxjz.ledger.navigation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import kotlin.math.abs

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
    // 最近一次由本组件自己发起的切页。同一次组合里同步完成的切换（见下面的 settleToTarget）
    // 不该再回传给调用方，否则会绕成「回传 → 改状态 → 再切页」的环
    var handledPage by remember { mutableIntStateOf(currentTab.ordinal) }

    LaunchedEffect(pagerState.settledPage) {
        val settled = pagerState.settledPage
        if (settled == handledPage) return@LaunchedEffect
        handledPage = settled
        latestOnSelect(MainTab.ofIndex(settled))
    }
    LaunchedEffect(currentTab) {
        val target = currentTab.ordinal
        if (pagerState.targetPage == target) return@LaunchedEffect
        // 跨两页（明细 ↔ 账户）直接定位：中间那页只是路过，滑过去没有任何信息量，
        // 只是让用户多等一次动画。相邻页保持动画，横滑的手感才连贯（v1.9.6 第 7 条）
        //
        // scrollToPage 底层是 snapToItem(id, requestRemeasure = true)，只搬位置不刷 settledPage；
        // 而 PagerState.scroll 只在「开滚前没在滚」的空档里把 settledPage 对齐到 currentPage。
        // 于是在「滚完立刻改目标」这种紧挨着的场景里，落定页会一直停在旧值：底栏高亮跟着旧值走，
        // 按返回也退回旧页。补完动画后再让滚动状态对齐一次，把这个缺口补上。
        if (abs(pagerState.currentPage - target) > 1) {
            pagerState.scrollToPage(target)
        } else {
            pagerState.animateScrollToPage(target)
        }
        pagerState.settleToTarget()
        // 这一步是同步完成的：settledPage 的更新，以及上面那个 LaunchedEffect 的触发与执行，
        // 都可能落在写 handledPage 之前。先记下来，避免把调用方刚下发的目标又回传给它。
        handledPage = pagerState.settledPage
    }

    HorizontalPager(
        state = pagerState,
        // 三页常驻（v1.9.6 第 7 条）。默认值 0 意味着只有当前页被组合：每次切页，目标页
        // 都要从零组合——新建 ViewModel、发起查询、跑完整测量布局，低端机上就是一两百毫秒
        // 的白屏。常驻的代价是三页的 ViewModel 同时存在、同时订阅数据库，但本项目是
        // Room 本地单表聚合，这点开销远小于每次切页重建。
        beyondViewportPageCount = MainTab.entries.size,
        modifier = modifier.fillMaxSize().testTag("main_pager"),
    ) { index ->
        pageContent(MainTab.ofIndex(index))
    }
}

/**
 * 把「落定页」对齐到当前位置。
 *
 * `PagerState.scroll` 的唯一副作用是：开滚前若不在滚动中，就把 settledPage 置为 currentPage；
 * 而 `scrollToPage` 会先 `awaitScrollDependencies` 再 `snapToItem`，当它紧跟在一次刚结束的
 * 动画后面时，那个空档已经错过了，settledPage 就一直停在旧页。这里补一次滚动（位移 0），
 * 让 PagerState 自己把状态对齐，不用反射去碰 private 的 settledPageState。
 */
private suspend fun PagerState.settleToTarget() {
    if (settledPage == currentPage) return
    scroll { }
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
        // 「更多明细」带上首页正在看的月份，点进去停在同一个月
        onOpenAllTxns = { monthKey -> navController.navigate(Routes.allTxns(monthKey)) },
    )
