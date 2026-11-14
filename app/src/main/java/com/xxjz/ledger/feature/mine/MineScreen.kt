package com.xxjz.ledger.feature.mine

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Receipt
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xxjz.ledger.core.common.MoneyFormatter

private data class MineEntry(
    val title: String,
    val icon: ImageVector,
    val enabled: Boolean,
    val onClick: () -> Unit,
)

/** P12「我的」：资产卡片 + 功能入口（AC-25）。 */
@Composable
fun MineScreen(
    onOpenAccounts: () -> Unit = {},
    onOpenTemplates: () -> Unit = {},
    onOpenRecurring: () -> Unit = {},
    onOpenReimbursement: () -> Unit = {},
    onOpenBackup: () -> Unit = {},
    onOpenCsvExport: () -> Unit = {},
    onOpenTrash: () -> Unit = {},
    onOpenManage: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    viewModel: MineViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val entries =
        listOf(
            MineEntry("账户管理", Icons.Outlined.AccountBalanceWallet, true, onOpenAccounts),
            MineEntry("记账模板", Icons.Outlined.Bookmarks, true, onOpenTemplates),
            MineEntry("周期记账", Icons.Outlined.Autorenew, true, onOpenRecurring),
            MineEntry("待报销", Icons.Outlined.Receipt, true, onOpenReimbursement),
            MineEntry("备份与恢复", Icons.Outlined.Backup, true, onOpenBackup),
            MineEntry("导出 CSV", Icons.Outlined.TableChart, true, onOpenCsvExport),
            MineEntry("分类与标签", Icons.Outlined.Category, true, onOpenManage),
            MineEntry("回收站", Icons.Outlined.Delete, true, onOpenTrash),
            MineEntry("设置", Icons.Outlined.Settings, true, onOpenSettings),
        )

    Column(Modifier.fillMaxSize().testTag("screen_mine")) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("净资产", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(
                    text = "¥${MoneyFormatter.centsToYuan(state.summary.net)}",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.testTag("mine_net_assets"),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "资产 ¥${MoneyFormatter.centsToYuan(state.summary.assets)} · " +
                        "负债 ¥${MoneyFormatter.centsToYuan(state.summary.liabilities)} · " +
                        "${state.accountCount} 个账户",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        Text(
            "功能",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 20.dp, top = 8.dp, bottom = 4.dp),
        )
        LazyColumn(Modifier.fillMaxWidth()) {
            items(entries) { entry ->
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable(enabled = entry.enabled, onClick = entry.onClick)
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        entry.icon,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                        tint =
                            if (entry.enabled) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outline
                            },
                    )
                    Spacer(Modifier.size(12.dp))
                    Text(entry.title, style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.weight(1f))
                    if (!entry.enabled) {
                        Text(
                            "即将上线",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Icon(
                            Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            item {
                Text(
                    "外观",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp),
                )
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("收支配色反转", style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.weight(1f))
                    Switch(
                        checked = state.amountColorsReversed,
                        onCheckedChange = { viewModel.toggleAmountColors() },
                        modifier = Modifier.testTag("switch_amount_reversed"),
                    )
                }
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("深色模式", style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.weight(1f))
                    FilterChip(
                        selected = state.themeMode == "SYSTEM",
                        onClick = { viewModel.setThemeMode("SYSTEM") },
                        label = { Text("跟随系统") },
                    )
                    Spacer(Modifier.size(8.dp))
                    FilterChip(
                        selected = state.themeMode == "LIGHT",
                        onClick = { viewModel.setThemeMode("LIGHT") },
                        label = { Text("浅色") },
                    )
                    Spacer(Modifier.size(8.dp))
                    FilterChip(
                        selected = state.themeMode == "DARK",
                        onClick = { viewModel.setThemeMode("DARK") },
                        label = { Text("深色") },
                    )
                }
            }
        }
    }
}
