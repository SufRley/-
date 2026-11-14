package com.xxjz.ledger.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.PieChart
import androidx.compose.material.icons.outlined.Receipt
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

private fun iconOf(tab: MainTab): ImageVector =
    when (tab) {
        MainTab.LEDGER -> Icons.Outlined.Receipt
        MainTab.REPORT -> Icons.Outlined.PieChart
        MainTab.ACCOUNTS -> Icons.Outlined.AccountBalance
    }

/**
 * 主框架：底部三 Tab（v1.8.0 / PRD D8）。
 *
 * v1.7.0 的「中央凸起＋」已经去掉：按新设计，「＋」是**明细页右下角的悬浮按钮**（D9），
 * 压在内容之上而不是嵌进底栏，所以这里不再需要 Box 叠 diag 的那套套路。
 */
@Composable
fun MainScreen(
    currentRoute: String?,
    onSelectTab: (MainTab) -> Unit,
    showBottomBar: Boolean = MainChrome.showBottomBar(currentRoute),
    content: @Composable () -> Unit,
) {
    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                XxjzBottomBar(currentRoute = currentRoute, onSelect = onSelectTab)
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) { content() }
    }
}

/** 底部导航栏：三个等宽 Tab，纯图标 + 短标签。 */
@Composable
fun XxjzBottomBar(
    currentRoute: String?,
    onSelect: (MainTab) -> Unit,
) {
    Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
        NavigationBar {
            MainTab.entries.forEach { tab ->
                NavigationBarItem(
                    selected = currentRoute == tab.route,
                    onClick = { onSelect(tab) },
                    icon = { Icon(iconOf(tab), contentDescription = tab.label) },
                    label = { Text(tab.label) },
                    modifier = Modifier.testTag("tab_${tab.route}"),
                )
            }
        }
    }
}

/**
 * 明细页右下角的悬浮「＋」（PRD D9）。
 *
 * 用 [FloatingActionButton] 而不是自绘 Surface：Ripple、阴影、动效都跟着 Material3 走，
 * 也让「什么时候显示」这件事由调用方（明细页）决定，不再需要主框架做路由判断。
 */
@Composable
fun AddFab(
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    FloatingActionButton(
        onClick = onClick,
        modifier = modifier.testTag("add_button"),
        shape = CircleShape,
    ) {
        Icon(Icons.Filled.Add, contentDescription = "记一笔", modifier = Modifier.size(26.dp))
    }
}
