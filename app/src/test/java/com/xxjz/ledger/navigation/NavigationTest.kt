package com.xxjz.ledger.navigation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * 导航与主框架（v1.8.0 / PRD F5、D8–D11；v1.9.5 第 2 条改版）。
 *
 * 这些是「看一眼截图就能骗过自己」的规则，所以全部表驱动钉死：改 Tab、改抽屉、改 FAB 显隐
 * 只要没同步改这里，测试立刻红。
 *
 * v1.9.5 第 2 条把三个 Tab 合进了 [Routes.MAIN] 一个目的地（为了让横滑不重建 ViewModel），
 * 于是「主 Tab 页」这件事从三条路由变成一条——这里对应的断言也跟着换口径，
 * 但**意图没变**：主框架页必须有底栏和 ☰、二级页必须没有。
 */
@DisplayName("导航与主框架")
class NavigationTest {
    @Test
    @DisplayName("底部只剩三个 Tab：明细 / 报表 / 账户（D8）")
    fun threeTabs() {
        assertEquals(listOf("明细", "报表", "账户"), MainTab.entries.map { it.label })
        assertEquals(
            listOf("ledger", "report", "accounts"),
            MainTab.entries.map { it.key },
        )
        assertFalse(MainTab.entries.any { it.label == "我的" }, "「我的」Tab 必须取消")
        assertFalse(MainTab.entries.any { it.label == "预算" }, "「预算」不再是 Tab")
    }

    @Test
    @DisplayName("三个 Tab 住同一个目的地，横滑只换页不换目的地")
    fun tabsShareOneDestination() {
        assertEquals(setOf(Routes.MAIN), MAIN_TAB_ROUTES)
        assertEquals(3, MainTab.entries.size, "页数与 Tab 数必须一致，否则 pager 会少一页或多一页空页")
    }

    @Test
    @DisplayName("Tab 下标与 key 双向换算，越界/认不出都有确定兜底")
    fun tabIndexMapping() {
        MainTab.entries.forEach { tab ->
            assertEquals(tab, MainTab.ofIndex(tab.ordinal))
            assertEquals(tab, MainTab.ofKey(tab.key))
        }
        assertEquals(MainTab.ACCOUNTS, MainTab.ofIndex(99), "越界夹到末页，宁可停一页也不要崩")
        assertEquals(MainTab.LEDGER, MainTab.ofIndex(-3), "负数同样夹回首屏")
        assertNull(MainTab.ofKey("nope"))
        assertNull(MainTab.ofKey(null))
    }

    @Test
    @DisplayName("抽屉七项，顺序固定，且不放借贷（D11）")
    fun drawerEntries() {
        assertEquals(7, DrawerEntry.entries.size)
        assertEquals(
            listOf("模板", "周期记账", "备份与恢复", "CSV 导出", "回收站", "分类管理", "设置"),
            DrawerEntry.entries.map { it.label },
        )
        assertEquals(
            listOf(
                Routes.TEMPLATES,
                Routes.RECURRING,
                Routes.BACKUP,
                Routes.CSV_EXPORT,
                Routes.TRASH,
                Routes.MANAGE,
                Routes.SETTINGS,
            ),
            DrawerEntry.entries.map { it.route },
        )
    }

    @Test
    @DisplayName("主框架页有底栏、有 ☰")
    fun mainTabChrome() {
        assertTrue(MainChrome.showBottomBar(Routes.MAIN))
        assertTrue(MainChrome.showDrawer(Routes.MAIN))
    }

    @ParameterizedTest
    @ValueSource(strings = ["budget", "mine", "templates", "settings", "backup", "asset_report", "recurring", "trash"])
    @DisplayName("二级页没有底栏、没有 ☰、没有 FAB")
    fun detailPagesChrome(route: String) {
        assertFalse(MainChrome.showBottomBar(route), "$route 是二级页，不该有底栏")
        assertFalse(MainChrome.showDrawer(route), "$route 是二级页，左上角应是返回箭头")
        assertFalse(MainChrome.showFab(route))
    }

    @Test
    @DisplayName("悬浮＋只在明细页出现（D9）")
    fun fabOnlyOnLedger() {
        assertTrue(MainChrome.showFab(Routes.MAIN, MainTab.LEDGER))
        assertFalse(MainChrome.showFab(Routes.MAIN, MainTab.REPORT), "报表页有底栏但不应有 ＋")
        assertFalse(MainChrome.showFab(Routes.MAIN, MainTab.ACCOUNTS), "账户页有底栏但不应有 ＋")
        assertFalse(MainChrome.showFab(null), "路由未知时一律不显示")
        assertFalse(MainChrome.showFab(Routes.SETTINGS, MainTab.LEDGER), "二级页不该有 ＋")
    }

    @Test
    @DisplayName("路由未知或为空时按二级页处理，不会误伤固定底栏")
    fun unknownRoute() {
        assertFalse(MainChrome.showBottomBar(null))
        assertFalse(MainChrome.showDrawer(""))
        assertFalse(MainChrome.showFab("unknown/route"))
    }

    @Test
    @DisplayName("资产报表是独立二级路由（账户页右上角进入）")
    fun assetReportRoute() {
        assertEquals("asset_report", Routes.ASSET_REPORT)
        assertFalse(MainChrome.showBottomBar(Routes.ASSET_REPORT))
    }

    @Test
    @DisplayName("主框架页不是二级页，底栏不会消失（v1.8.1 回归）")
    fun mainTabIsNotDetail() {
        assertFalse(MainChrome.isDetail(Routes.MAIN), "main 是主 Tab，若判成二级页底栏会整条消失")
    }

    @Test
    @DisplayName("isDetail 与 showBottomBar 严格互斥，杜绝两套判定各说各话")
    fun isDetailIsComplementOfBottomBar() {
        val samples =
            listOf(
                Routes.MAIN,
                Routes.SEARCH, Routes.CALENDAR, Routes.TEMPLATES, Routes.SETTINGS,
                Routes.BACKUP, Routes.TRASH, Routes.ASSET_REPORT,
                Routes.REIMBURSEMENT, Routes.ONBOARDING, null, "",
            )
        samples.forEach { r ->
            assertEquals(
                !MainChrome.showBottomBar(r),
                MainChrome.isDetail(r),
                "路由 $r 的 isDetail 与 showBottomBar 不一致",
            )
        }
    }

    @Test
    @DisplayName("全部明细是二级页，路由带首页当前月份（v1.9.6 第 2 条）")
    fun allTxnsRoute() {
        assertEquals("all_txns?month=202611", Routes.allTxns(202611))
        assertFalse(MainChrome.showBottomBar(Routes.ALL_TXNS), "全部明细是二级页，不该有底栏")
        assertFalse(MainChrome.showFab(Routes.ALL_TXNS), "也不该有悬浮＋")
    }
}
