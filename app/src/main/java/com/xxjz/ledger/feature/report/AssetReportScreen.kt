package com.xxjz.ledger.feature.report

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

/**
 * 资产报表页占位（S4 会替换成四板块完整实现）。
 *
 * 先占位是为了让 S2 的账户页右上角入口能真实点进去、路由能提前跑通回归，
 * 避免等到最后一步才发现导航参数或返回栈有问题。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssetReportScreen(onBack: () -> Unit) {
    Scaffold(
        modifier = Modifier.fillMaxSize().testTag("screen_asset_report"),
        topBar = {
            TopAppBar(
                title = { Text("资产报表") },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("asset_report_back")) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            Text("资产报表建设中")
        }
    }
}
