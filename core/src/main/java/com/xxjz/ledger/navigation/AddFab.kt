package com.xxjz.ledger.navigation

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

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
