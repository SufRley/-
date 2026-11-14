package com.xxjz.ledger.navigation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * 导航与主框架（v1.8.0 / PRD F5、D8–D11）。
 *
 * 这些是「看一眼截图就能骗过自己」的规则，所以全部表驱动钉死：改 Tab、改抽屉、改 FAB 显隐
 * 只要没同步改这里，测试立刻红。
 */
@DisplayName("导航与主框架")
class NavigationTest {
    @Test
    @DisplayName("底部只剩三个 Tab：明细 / 报表 / 账户（D8）")
    fun threeTabs() {
        assertEquals(listOf("明细", "报表", "账户"), MainTab.entries.map { it.label })
        assertEquals(
            listOf(Routes.LEDGER, Routes.REPORT, Routes.ACCOUNTS),
            MainTab.entries.map { it.route },
        )
        assertFalse(MainTab.entries.any { it.route == Routes.MINE }, "「我的」Tab 必须取消")
        assertFalse(MainTab.entries.any { it.route == Routes.BUDGET }, "「预算」不再是 Tab")
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
        assertFalse(DrawerEntry.entries.any { it.route == Routes.DEBTS }, "借贷不进抽屉，入口在账户页")
    }

    @ParameterizedTest
    @ValueSource(strings = ["ledger", "report", "accounts"])
    @DisplayName("三个主 Tab 有底栏、有 ☰、没有 FAB（除明细）")
    fun mainTabsChrome(route: String) {
        assertTrue(MainChrome.showBottomBar(route))
        assertTrue(MainChrome.showDrawer(route))
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
        assertTrue(MainChrome.showFab(Routes.LEDGER))
        assertFalse(MainChrome.showFab(Routes.REPORT), "报表页有底栏但不应有 ＋")
        assertFalse(MainChrome.showFab(Routes.ACCOUNTS), "账户页有底栏但不应有 ＋")
        assertFalse(MainChrome.showFab(null), "路由未知时一律不显示")
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

    @ParameterizedTest
    @ValueSource(strings = ["ledger", "report", "accounts"])
    @DisplayName("三个主 Tab 都不是二级页，底栏不会消失（v1.8.1 回归）")
    fun mainTabsAreNotDetail(route: String) {
        assertFalse(MainChrome.isDetail(route), "$route 是主 Tab，若判成二级页底栏会整条消失")
    }

    @Test
    @DisplayName("isDetail 与 showBottomBar 严格互斥，杜绝两套判定各说各话")
    fun isDetailIsComplementOfBottomBar() {
        val samples =
            listOf(
                Routes.LEDGER, Routes.REPORT, Routes.ACCOUNTS,
                Routes.SEARCH, Routes.CALENDAR, Routes.TEMPLATES, Routes.SETTINGS,
                Routes.BACKUP, Routes.TRASH, Routes.ASSET_REPORT, Routes.DEBTS,
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
}
