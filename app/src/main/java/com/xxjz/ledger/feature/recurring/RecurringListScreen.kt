package com.xxjz.ledger.feature.recurring

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xxjz.ledger.core.common.MoneyFormatter
import com.xxjz.ledger.core.designsystem.component.EmptyState
import com.xxjz.ledger.domain.model.TxnType

/** P16 周期任务列表（AC-53 / AC-54 / AC-57）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecurringListScreen(
    onBack: () -> Unit = {},
    onEdit: (id: String?) -> Unit = {},
    viewModel: RecurringListViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("周期记账") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            FloatingActionButton(onClick = { onEdit(null) }, modifier = Modifier.testTag("fab_add_recurring")) {
                Icon(Icons.Filled.Add, contentDescription = "新建周期任务")
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).testTag("screen_recurring")) {
            if (!state.loading && state.rows.isEmpty()) {
                EmptyState(
                    title = "还没有周期任务",
                    description = "房租、会员费这类固定支出，设一次就会自动记账",
                    modifier = Modifier.fillMaxWidth().padding(top = 64.dp),
                    actionText = "新建周期任务",
                    onAction = { onEdit(null) },
                )
            } else {
                Text(
                    text = "进行中 ${state.activeCount} 个",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                )
                LazyColumn(Modifier.fillMaxSize()) {
                    items(state.rows, key = { it.rule.id }) { row ->
                        RecurringRowItem(
                            row = row,
                            onToggle = { viewModel.toggle(row.rule.id) },
                            onRunNow = { viewModel.runNow(row.rule.id) },
                            onDelete = { viewModel.delete(row.rule.id) },
                            onOpen = { onEdit(row.rule.id) },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(start = 16.dp))
                    }
                    item { Spacer(Modifier.height(88.dp)) }
                }
            }
        }
    }
}

@Composable
private fun RecurringRowItem(
    row: RecurringRow,
    onToggle: () -> Unit,
    onRunNow: () -> Unit,
    onDelete: () -> Unit,
    onOpen: () -> Unit,
) {
    val dimmed = row.status != RecurringStatus.ACTIVE
    Column(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = row.title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = if (dimmed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.size(8.dp))
                    StatusChip(row.status)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text =
                        buildString {
                            append(row.periodLabel)
                            append(" · 下次 ")
                            append(row.nextRunLabel)
                            if (row.categoryName.isNotBlank()) {
                                append(" · ")
                                append(row.categoryName)
                            }
                        },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = (if (row.rule.type == TxnType.INCOME) "+" else "-") + "¥${MoneyFormatter.centsToYuan(row.rule.amount)}",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.testTag("recurring_amount_${row.rule.id}"),
            )
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (row.rule.paused) "暂停中" else "自动记账开",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
            Switch(
                checked = !row.rule.paused,
                onCheckedChange = { onToggle() },
                modifier = Modifier.padding(start = 8.dp).testTag("recurring_switch_${row.rule.id}"),
            )
            Spacer(Modifier.weight(1f))
            if (row.status != RecurringStatus.FINISHED) {
                TextButton(onClick = onRunNow, modifier = Modifier.testTag("recurring_run_${row.rule.id}")) {
                    Icon(Icons.Outlined.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(4.dp))
                    Text("立即执行")
                }
            }
            IconButton(onClick = onDelete, modifier = Modifier.testTag("recurring_delete_${row.rule.id}")) {
                Icon(
                    Icons.Outlined.Delete,
                    contentDescription = "删除",
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun StatusChip(status: RecurringStatus) {
    val (text, color) =
        when (status) {
            RecurringStatus.ACTIVE -> "进行中" to MaterialTheme.colorScheme.primary
            RecurringStatus.PAUSED -> "已暂停" to MaterialTheme.colorScheme.outline
            RecurringStatus.FINISHED -> "已结束" to MaterialTheme.colorScheme.outline
        }
    Box(
        modifier =
            Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(color.copy(alpha = 0.12f))
                .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = color)
    }
}
