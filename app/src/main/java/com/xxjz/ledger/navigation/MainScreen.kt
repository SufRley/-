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
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

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

/**
 * 底部导航栏：三个等宽 Tab，**纯汉字、无图标**（PRD v1.9.0 第 1 条）。
 *
 * 为什么去掉图标：屏宽 360dp 除以 3 只剩 120dp 一格，图标 + 汉字挤在一起时汉字只有
 * `labelMedium` 那么小，读起来比没有图标还费劲。只留汉字、字号抬到 `titleSmall`，
 * 反而更好认。也保留 `NavigationBarItem` 而不是自绘 Row——选中指示器、水波纹、
 * 无障碍角色都是 Material3 现成的。
 */
@Composable
fun XxjzBottomBar(
    currentRoute: String?,
    onSelect: (MainTab) -> Unit,
) {
    Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
        NavigationBar {
            MainTab.entries.forEach { tab ->
                val selected = currentRoute == tab.route
                NavigationBarItem(
                    selected = selected,
                    onClick = { onSelect(tab) },
                    // 没有图标时 NavigationBarItem 会把 label 居中，
                    // 传空 icon 即可，不必绕开这个组件
                    icon = {},
                    label = {
                        Text(
                            text = tab.label,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            color =
                                if (selected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                        )
                    },
                    // 没有图标之后，读屏需要显式告知这一项讲的是什么
                    modifier =
                        Modifier
                            .testTag("tab_${tab.route}")
                            .semantics { contentDescription = tab.label },
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
