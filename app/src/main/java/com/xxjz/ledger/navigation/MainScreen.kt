package com.xxjz.ledger.navigation

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.DonutLarge
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.DonutLarge
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * 主框架：底部三 Tab（v1.8.0 / PRD D8）。
 *
 * v1.7.0 的「中央凸起＋」已经去掉：按新设计，「＋」是**明细页右下角的悬浮按钮**（D9），
 * 压在内容之上而不是嵌进底栏，所以这里不再需要 Box 叠 diag 的那套套路。
 *
 * v1.9.5 第 2 条起，底栏的高亮由 [currentTab] 决定而不是路由：三个 Tab 已经是同一个
 * 目的地里的三页，路由区分不出来了。
 */
@Composable
fun MainScreen(
    currentTab: MainTab,
    onSelectTab: (MainTab) -> Unit,
    showBottomBar: Boolean = true,
    content: @Composable () -> Unit,
) {
    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                XxjzBottomBar(currentTab = currentTab, onSelect = onSelectTab)
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) { content() }
    }
}

/**
 * 底部导航栏：三个 Tab，**只显示图标、不显示文字**（PRD v1.9.0 第 2 条）。
 *
 * 上一版把「去掉汉字」做成了「去掉图标」，反了：用户要的是「别写字，用小图标」。
 * 这里选中用实心图标 + 主色 + 指示胶囊，未选中用线性图标 + 灰，选中时再来一点点放大，
 * 让「我现在在哪个 Tab」一眼可辨，不需要文字帮忙。
 */
@Composable
fun XxjzBottomBar(
    currentTab: MainTab,
    onSelect: (MainTab) -> Unit,
) {
    Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
        NavigationBar {
            MainTab.entries.forEach { tab ->
                val selected = currentTab == tab
                // 选中轻微放大：纯图标导航少了文字锚点，这点动效就是最重要的位置提示
                val scale by animateFloatAsState(
                    targetValue = if (selected) 1.1f else 0.96f,
                    label = "tab_scale",
                )
                NavigationBarItem(
                    selected = selected,
                    onClick = { onSelect(tab) },
                    icon = {
                        Icon(
                            imageVector = tabIcon(tab, selected),
                            // 不显示文字，但读屏仍要能念出这一项是什么
                            contentDescription = tab.label,
                            modifier =
                                Modifier
                                    .size(26.dp)
                                    .graphicsLayer {
                                        scaleX = scale
                                        scaleY = scale
                                    },
                        )
                    },
                    label = null,
                    alwaysShowLabel = false,
                    colors =
                        NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                        ),
                    modifier = Modifier.testTag("tab_${tab.key}"),
                )
            }
        }
    }
}

/**
 * 每个 Tab 的图标：选中用实心、未选中用线性。
 *
 * 挑图标的偏心思路——「明细」用带锯齿边的小票（ReceiptLong）、「报表」用甜甜圈图（DonutLarge）、
 * 「账户」用钱包（AccountBalanceWallet）：三个形状差异大，缩到 26dp 也不会看混。
 */
private fun tabIcon(
    tab: MainTab,
    selected: Boolean,
): ImageVector =
    when (tab) {
        MainTab.LEDGER -> if (selected) Icons.Filled.ReceiptLong else Icons.Outlined.ReceiptLong
        MainTab.REPORT -> if (selected) Icons.Filled.DonutLarge else Icons.Outlined.DonutLarge
        MainTab.ACCOUNTS ->
            if (selected) Icons.Filled.AccountBalanceWallet else Icons.Outlined.AccountBalanceWallet
    }
