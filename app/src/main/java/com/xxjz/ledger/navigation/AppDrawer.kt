package com.xxjz.ledger.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

private fun iconOf(entry: DrawerEntry): ImageVector =
    when (entry) {
        DrawerEntry.TEMPLATES -> Icons.Outlined.AutoAwesome
        DrawerEntry.RECURRING -> Icons.Outlined.Sync
        DrawerEntry.BACKUP -> Icons.Outlined.Restore
        DrawerEntry.CSV_EXPORT -> Icons.Outlined.FileDownload
        DrawerEntry.TRASH -> Icons.Outlined.Delete
        DrawerEntry.MANAGE -> Icons.Outlined.Category
        DrawerEntry.SETTINGS -> Icons.Outlined.Settings
    }

/**
 * 左上角 ☰ 展开的半屏抽屉（v1.8.0 / PRD D10、D11）。
 *
 * 用 [ModalNavigationDrawer] 而不是自己写 sheet：点击遮罩关闭、返回键关闭、手势滑动关闭
 * 都由组件负责，少一堆状态要维护。
 *
 * 宽度取 **0.55 个屏**——PRD 说「半个页面」，但太窄的话「备份与恢复」这种两个字以上的
 * 条目会换行，取略多于一半既符合观感又不憋屈。
 * 抽屉**不放借贷**：借贷入口在账户页的入口卡（D11）。
 */
@Composable
fun XxjzDrawer(
    open: Boolean,
    onClose: () -> Unit,
    onOpenRoute: (String) -> Unit,
    content: @Composable () -> Unit,
) {
    // 抽屉开合用 LaunchedEffect 驱动同一个 DrawerState：这样点 ☰ 展开、点遮罩/返回键收起
    // 都能走组件自己的动画与手势，不需要我们在外面维护一个布尔量再和它对齐。
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    LaunchedEffect(open) {
        if (open && drawerState.isClosed) drawerState.open()
        if (!open && drawerState.isOpen) drawerState.close()
    }
    val scope = rememberCoroutineScope()
    // 用户手势或点遮罩收起抽屉时，把外部的 open 状态同步回来，否则下次点 ☰ 会因为
    // 「open 还是 true」而不触发展开。
    LaunchedEffect(drawerState.isClosed) {
        if (drawerState.isClosed && open) onClose()
    }
    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = open,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.fillMaxWidth(0.55f).fillMaxHeight().testTag("drawer_sheet"),
            ) {
                Spacer(Modifier.height(24.dp))
                Column(Modifier.padding(horizontal = 20.dp)) {
                    Text("一心记账", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "本地记账 · 数据不出手机",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                DrawerEntry.entries.forEach { entry ->
                    NavigationDrawerItem(
                        label = { Text(entry.label) },
                        icon = { Icon(iconOf(entry), contentDescription = null) },
                        selected = false,
                        onClick = {
                            // 先收起再跳，避免目标页在抽屉遮罩下先渲染一帧
                            scope.launch { drawerState.close() }
                            onClose()
                            onOpenRoute(entry.route)
                        },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp).testTag("drawer_${entry.name}"),
                    )
                }
            }
        },
        content = content,
    )
}
