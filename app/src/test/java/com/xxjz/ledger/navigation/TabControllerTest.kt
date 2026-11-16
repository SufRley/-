package com.xxjz.ledger.navigation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Tab 选中态与切页历史（v1.9.5 第 2 条 / AC-2.5）。
 *
 * 这几条是「按返回该回哪一页」的全部逻辑，也是最容易在重构里被悄悄改掉的地方，
 * 所以不掺 Compose，纯 Kotlin 钉死。
 */
@DisplayName("Tab 选中态 TabController")
class TabControllerTest {
    @Test
    @DisplayName("初始停在明细，没有可返回的历史")
    fun initialState() {
        val tabs = TabController()
        assertEquals(MainTab.LEDGER, tabs.current)
        assertFalse(tabs.canGoBack)
    }

    @Test
    @DisplayName("横向切一页后按返回：回到切页前的 Tab，而不是退出")
    fun backReturnsToPreviousTab() {
        val tabs = TabController()
        tabs.select(MainTab.REPORT)
        assertEquals(MainTab.REPORT, tabs.current)
        assertTrue(tabs.canGoBack, "切过页就该有历史可回（AC-2.5）")

        assertTrue(tabs.back())
        assertEquals(MainTab.LEDGER, tabs.current)
        assertFalse(tabs.canGoBack, "只切了一页，回完就没有历史了")
    }

    @Test
    @DisplayName("连切两页：返回逐页退回，最后交还系统")
    fun backWalksBackThroughHistory() {
        val tabs = TabController()
        tabs.select(MainTab.REPORT)
        tabs.select(MainTab.ACCOUNTS)

        assertTrue(tabs.back())
        assertEquals(MainTab.REPORT, tabs.current)
        assertTrue(tabs.back())
        assertEquals(MainTab.LEDGER, tabs.current)
        assertFalse(tabs.back(), "历史空了要返回 false，让系统去退出应用")
    }

    @Test
    @DisplayName("重复选同一个 Tab 不写历史，连点底栏不会把返回键吃掉")
    fun repeatedSelectIsIgnored() {
        val tabs = TabController()
        tabs.select(MainTab.REPORT)
        tabs.select(MainTab.REPORT)
        tabs.select(MainTab.REPORT)

        assertTrue(tabs.back())
        assertEquals(MainTab.LEDGER, tabs.current)
        assertFalse(tabs.canGoBack, "三次重复选中只该留一条历史")
    }

    @Test
    @DisplayName("A→B→A 之后返回：回到 B（历史按真实停留顺序记）")
    fun abaReturnsToMiddle() {
        val tabs = TabController()
        tabs.select(MainTab.REPORT)
        tabs.select(MainTab.LEDGER)

        assertEquals(MainTab.LEDGER, tabs.current)
        assertTrue(tabs.back())
        assertEquals(MainTab.REPORT, tabs.current)
    }

    @Test
    @DisplayName("来回横滑十几次：历史不超过三层，按三次返回就能退出")
    fun historyDepthIsCapped() {
        val tabs = TabController()
        repeat(6) {
            tabs.select(MainTab.REPORT)
            tabs.select(MainTab.LEDGER)
        }

        var steps = 0
        while (tabs.back()) steps++
        assertEquals(3, steps, "历史封顶三层，不能滑多少次就要按多少次返回")
    }
}
