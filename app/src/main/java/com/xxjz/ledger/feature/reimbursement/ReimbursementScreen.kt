package com.xxjz.ledger.feature.reimbursement

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xxjz.ledger.core.common.MoneyFormatter
import com.xxjz.ledger.core.designsystem.component.CompactHeader
import com.xxjz.ledger.core.designsystem.component.EmptyState

/** P26 待报销（AC-59 ~ AC-62）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReimbursementScreen(
    onBack: () -> Unit = {},
    viewModel: ReimbursementViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var confirming by remember { mutableStateOf<ReimbursementRow?>(null) }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    // v1.9.0 第 4 条：去掉 TopAppBar（64dp + 状态栏 inset），换紧凑头，
    // 与明细 / 报表 / 账户页同一起点，不再出现「这里空一块」。
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().testTag("screen_reimbursement")) {
            CompactHeader(
                title = "待报销",
                leading = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("reimburse_back")) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
            )
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text("待报销合计", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        "¥${MoneyFormatter.centsToYuan(state.pendingTotal)}",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.testTag("reimburse_total"),
                    )
                }
                Text(
                    "${state.pending.size} 笔",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            LazyColumn(Modifier.fillMaxSize()) {
                if (state.pending.isEmpty()) {
                    item {
                        EmptyState(
                            title = "没有待报销的账目",
                            description = "记账时打开「待报销」，出差垫付就不会忘",
                            modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
                        )
                    }
                } else {
                    item { SectionTitle("待报销账目") }
                    items(state.pending, key = { it.txn.id }) { row ->
                        ReimburseRow(row) {
                            confirming = row
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(start = 16.dp))
                    }
                }
                if (state.done.isNotEmpty()) {
                    item { SectionTitle("已报销") }
                    items(state.done, key = { "done-${it.txn.id}" }) { row ->
                        DoneRow(row, onUndo = { viewModel.undo(row.txn.id) })
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(start = 16.dp))
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
        SnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter))
    }

    confirming?.let { row ->
        ReimburseDialog(
            row = row,
            onDismiss = { confirming = null },
            onConfirm = { text ->
                viewModel.confirm(row.txn.id, text)
                confirming = null
            },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun ReimburseRow(
    row: ReimbursementRow,
    onReimburse: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(row.txn.note.ifBlank { row.categoryName }, style = MaterialTheme.typography.bodyLarge)
            Text(
                "${row.dateLabel} · ${row.categoryName}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text("¥${MoneyFormatter.centsToYuan(row.txn.amount)}", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        TextButton(onClick = onReimburse, modifier = Modifier.testTag("reimburse_btn_${row.txn.id}")) { Text("报销") }
    }
}

@Composable
private fun DoneRow(
    row: ReimbursementRow,
    onUndo: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(row.txn.note.ifBlank { row.categoryName }, style = MaterialTheme.typography.bodyLarge)
            Text(
                buildString {
                    append("已报 ¥")
                    append(MoneyFormatter.centsToYuan(row.txn.reimbursedAmount))
                    val self = row.txn.amount - row.txn.reimbursedAmount
                    if (self > 0) {
                        append(" · 自付 ¥")
                        append(MoneyFormatter.centsToYuan(self))
                    }
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        TextButton(onClick = onUndo, modifier = Modifier.testTag("reimburse_undo_${row.txn.id}")) { Text("撤销") }
    }
}

@Composable
private fun ReimburseDialog(
    row: ReimbursementRow,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(MoneyFormatter.centsToPlain(row.txn.amount)) }
    val cents = MoneyFormatter.yuanToCents(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("确认报销") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "支出 ¥${MoneyFormatter.centsToYuan(row.txn.amount)}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.filter { c -> c.isDigit() || c == '.' }.take(12) },
                    label = { Text("实际到账金额（元）") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.testTag("reimburse_input"),
                )
                Text(
                    text =
                        if (cents != null && cents in 1..row.txn.amount) {
                            "自付 ¥${MoneyFormatter.centsToYuan(row.txn.amount - cents)}"
                        } else {
                            "报销金额不能超过支出金额"
                        },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text) },
                enabled = cents != null && cents in 1..row.txn.amount,
                modifier = Modifier.testTag("reimburse_confirm"),
            ) { Text("确认") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
