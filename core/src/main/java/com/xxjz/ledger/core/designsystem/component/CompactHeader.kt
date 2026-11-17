package com.xxjz.ledger.core.designsystem.component

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 二级页面的紧凑标题栏（v1.9.0）。
 *
 * 用 `TopAppBar` 时高度是 64dp 再加状态栏 inset，比明细 / 报表自己画的那行标题高出一截，
 * 于是「切过去页面内容整体往下掉一下」。这个组件把内边距压到 `vertical = 4.dp`，
 * 与 `MonthHeader` / `WindowHeader` 同一口径，三个主 Tab 与各二级页的内容起点才能对齐。
 *
 * 刻意不用 `Scaffold`：调用方自己控制滚动区域，标题栏跟着内容一起不滚动。
 */
@Composable
fun CompactHeader(
    title: String,
    modifier: Modifier = Modifier,
    leading: @Composable () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f).padding(start = 8.dp),
        )
        actions()
    }
}
