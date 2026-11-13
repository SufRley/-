package com.xxjz.ledger.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PieChart
import androidx.compose.material.icons.outlined.Receipt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

private fun iconOf(tab: MainTab): ImageVector =
    when (tab) {
        MainTab.LEDGER -> Icons.Outlined.Receipt
        MainTab.REPORT -> Icons.Outlined.PieChart
        MainTab.BUDGET -> Icons.Outlined.AccountBalanceWallet
        MainTab.MINE -> Icons.Outlined.Person
    }

/**
 * 主框架：底部导航 + 中央凸起「＋」。
 * 「＋」用 Box 叠在 NavigationBar 之上，而不是塞进 NavigationBarItem（见 DEV_PLAN M0 技术点）。
 */
@Composable
fun MainScreen(
    currentRoute: String?,
    onSelectTab: (MainTab) -> Unit,
    onAddClick: () -> Unit,
    showBottomBar: Boolean = true,
    content: @Composable () -> Unit,
) {
    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                XxjzBottomBar(
                    currentRoute = currentRoute,
                    onSelect = onSelectTab,
                    onAddClick = onAddClick,
                )
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) { content() }
    }
}

/** 底部导航栏：标准 NavigationBar + 上层浮动的凸起「＋」。 */
@Composable
fun XxjzBottomBar(
    currentRoute: String?,
    onSelect: (MainTab) -> Unit,
    onAddClick: () -> Unit,
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
        AddButton(
            modifier = Modifier.align(Alignment.TopCenter).offset(y = (-16).dp),
            onClick = onAddClick,
        )
    }
}

/** 中央凸起的圆形「＋」按钮。 */
@Composable
fun AddButton(
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier.size(56.dp).testTag("add_button"),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primary,
        shadowElevation = 6.dp,
        onClick = onClick,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                Icons.Filled.Add,
                contentDescription = "记一笔",
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(30.dp),
            )
        }
    }
}
