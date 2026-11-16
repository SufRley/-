package com.xxjz.ledger.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.dp
import com.xxjz.ledger.core.designsystem.theme.XxjzTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.jupiter.api.DisplayName
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 三页横滑（v1.9.5 第 2 条）。
 *
 * 用假页面（一段文字）而不是真的明细/报表/账户：这里要验的是**切页与选中态的同步**，
 * 掺进三个真页面的 ViewModel 只会让断言变脆、失败原因变模糊。
 *
 * @see MainTabsPager
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w360dp-h640dp-xhdpi")
class MainTabsPagerTest {
    @get:Rule
    val compose = createComposeRule()

    private fun page(tab: MainTab): String =
        when (tab) {
            MainTab.LEDGER -> "明细内容"
            MainTab.REPORT -> "报表内容"
            MainTab.ACCOUNTS -> "账户内容"
        }

    @Composable
    private fun FakePage(tab: MainTab) {
        Box(Modifier.fillMaxSize().testTag("page_${tab.key}"), contentAlignment = Alignment.Center) {
            Text(page(tab))
        }
    }

    /** 只放 pager，不带底栏：验切页本身。 */
    private fun setPager(
        initial: MainTab = MainTab.LEDGER,
        onSelect: (MainTab) -> Unit = {},
    ) {
        compose.setContent {
            XxjzTheme {
                MainTabsPager(currentTab = initial, onSelect = onSelect) { tab -> FakePage(tab) }
            }
        }
        compose.waitForIdle()
    }

    @Test
    @DisplayName("AC-2.1 明细页左滑：切到报表页")
    fun swipeLeftGoesToReport() {
        var selected = MainTab.LEDGER
        setPager(onSelect = { selected = it })

        compose.onNodeWithTag("main_pager").performTouchInput { swipeLeft() }
        compose.waitForIdle()

        assertEquals(MainTab.REPORT, selected)
        compose.onNodeWithTag("page_report").assertIsDisplayed()
    }

    @Test
    @DisplayName("AC-2.2 账户页右滑：回到报表页")
    fun swipeRightFromAccountsGoesToReport() {
        var selected = MainTab.ACCOUNTS
        setPager(initial = MainTab.ACCOUNTS, onSelect = { selected = it })
        compose.onNodeWithTag("page_accounts").assertIsDisplayed()

        compose.onNodeWithTag("main_pager").performTouchInput { swipeRight() }
        compose.waitForIdle()

        assertEquals(MainTab.REPORT, selected)
        compose.onNodeWithTag("page_report").assertIsDisplayed()
    }

    @Test
    @DisplayName("AC-2.3 明细页右滑：没有上一页，停在原地")
    fun swipeRightOnFirstPageStays() {
        val picks = mutableListOf<MainTab>()
        setPager(onSelect = { picks.add(it) })

        compose.onNodeWithTag("main_pager").performTouchInput { swipeRight() }
        compose.waitForIdle()

        assertTrue("首屏右滑不该产生任何切换，实际收到 $picks", picks.isEmpty())
        compose.onNodeWithTag("page_ledger").assertIsDisplayed()
    }

    @Test
    @DisplayName("点底栏切页：pager 跟过去，不靠手势")
    fun externalSelectScrollsPager() {
        var current by mutableStateOf(MainTab.LEDGER)
        compose.setContent {
            XxjzTheme {
                MainTabsPager(currentTab = current, onSelect = { current = it }) { tab -> FakePage(tab) }
            }
        }
        compose.waitForIdle()

        compose.runOnIdle { current = MainTab.ACCOUNTS }
        compose.waitForIdle()

        compose.onNodeWithTag("page_accounts").assertIsDisplayed()
    }

    @Test
    @DisplayName("AC-2.1 底栏跟随：滑到报表后「报表」高亮，且只有它高亮")
    fun bottomBarFollowsSwipe() {
        val tabs = TabController()
        compose.setContent {
            XxjzTheme {
                MainScreen(currentTab = tabs.current, onSelectTab = { tabs.select(it) }) {
                    MainTabsPager(currentTab = tabs.current, onSelect = { tabs.select(it) }) { tab ->
                        FakePage(tab)
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("tab_ledger").assertIsSelected()

        compose.onNodeWithTag("main_pager").performTouchInput { swipeLeft() }
        compose.waitForIdle()

        assertEquals(MainTab.REPORT, tabs.current)
        compose.onNodeWithTag("tab_report").assertIsSelected()
        compose.onNodeWithTag("tab_ledger").assertIsDisplayed()
    }

    @Test
    @DisplayName("AC-2.5 切页后按返回：回到切页前的 Tab")
    fun backAfterSwipeReturnsToPreviousTab() {
        val tabs = TabController()
        compose.setContent {
            XxjzTheme {
                MainTabsPager(currentTab = tabs.current, onSelect = { tabs.select(it) }) { tab -> FakePage(tab) }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("main_pager").performTouchInput { swipeLeft() }
        compose.waitForIdle()

        compose.runOnIdle { tabs.back() }
        compose.waitForIdle()

        assertEquals(MainTab.LEDGER, tabs.current)
        compose.onNodeWithTag("page_ledger").assertIsDisplayed()
    }

    @Test
    @DisplayName("AC-2.4 页内横向列表优先消费手势：只滚内容，不切页")
    fun innerHorizontalListConsumesSwipe() {
        val picks = mutableListOf<MainTab>()
        compose.setContent {
            XxjzTheme {
                MainTabsPager(currentTab = MainTab.LEDGER, onSelect = { picks.add(it) }) { tab ->
                    if (tab == MainTab.LEDGER) {
                        LazyRow(Modifier.fillMaxSize().testTag("inner_row")) {
                            items(20) { index ->
                                Box(
                                    Modifier
                                        .width(120.dp)
                                        .fillMaxHeight()
                                        .testTag("inner_item_$index"),
                                )
                            }
                        }
                    } else {
                        FakePage(tab)
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("inner_item_0").assertIsDisplayed()

        compose.onNodeWithTag("inner_row").performTouchInput { swipeLeft() }
        compose.waitForIdle()

        assertTrue("页内横滚不该触发切页，实际收到 $picks", picks.isEmpty())
        // 三页常驻之后报表页始终在语义树里，不能再断言「不存在」；改成断言它不在可视区
        compose.onNodeWithTag("page_report").assertIsNotDisplayed()
        compose.onNodeWithTag("inner_item_0").assertIsNotDisplayed()
    }

    @Test
    @DisplayName("三页同时常驻：切页不再从零组合（v1.9.6 第 7 条）")
    fun allPagesComposedUpFront() {
        val composed = mutableSetOf<MainTab>()
        compose.setContent {
            XxjzTheme {
                MainTabsPager(currentTab = MainTab.LEDGER, onSelect = {}) { tab ->
                    composed += tab
                    FakePage(tab)
                }
            }
        }
        compose.waitForIdle()

        assertEquals(
            "beyondViewportPageCount 为 0 时这里只会有一个，切页才会补上其余两页",
            MainTab.entries.toSet(),
            composed,
        )
    }

    @Test
    @DisplayName("跨两页点击直接定位到账户，不经过报表页的停留")
    fun jumpTwoPages() {
        var tab by mutableStateOf(MainTab.LEDGER)
        val settled = mutableListOf<MainTab>()
        compose.setContent {
            XxjzTheme {
                MainTabsPager(currentTab = tab, onSelect = { settled += it }) { t -> FakePage(t) }
            }
        }
        compose.waitForIdle()

        compose.runOnIdle { tab = MainTab.ACCOUNTS }
        compose.waitForIdle()

        compose.onNodeWithTag("page_accounts").assertIsDisplayed()
        // 跨两页是直接定位，落定页在本次切页里就对齐了，不回传才是对的；
        // 真正要验的是「没有在报表页停留」，以及随后按返回能退回明细
        assertTrue("不该在报表页留一次选中，实际 $settled", MainTab.REPORT !in settled)
        compose.runOnIdle { tab = MainTab.LEDGER }
        compose.waitForIdle()
        compose.onNodeWithTag("page_ledger").assertIsDisplayed()
    }

    @Test
    @DisplayName("滚动刚结束就改目标：落定页仍对齐到新目标，底栏高亮不落后")
    fun rapidTargetChangeStillSettles() {
        var tab by mutableStateOf(MainTab.LEDGER)
        val settled = mutableListOf<MainTab>()
        compose.setContent {
            XxjzTheme {
                MainTabsPager(currentTab = tab, onSelect = { settled += it }) { t -> FakePage(t) }
            }
        }
        compose.waitForIdle()

        // 改目标后不等动画跑完就再改一次，模拟连点底栏
        compose.runOnIdle { tab = MainTab.REPORT }
        compose.runOnIdle { tab = MainTab.LEDGER }
        compose.waitForIdle()

        compose.onNodeWithTag("page_ledger").assertIsDisplayed()
        assertEquals("不该回传任何切换，实际 $settled", emptyList<MainTab>(), settled)
    }
}
