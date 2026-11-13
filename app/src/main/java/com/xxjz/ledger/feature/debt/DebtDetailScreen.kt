package com.xxjz.ledger.feature.debt

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import com.xxjz.ledger.core.common.DateUtils
import com.xxjz.ledger.core.common.MoneyFormatter
import com.xxjz.ledger.domain.model.DebtDirection

/** P19 借贷详情 + 记还款（AC-64、AC-65）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebtDetailScreen(
    onBack: () -> Unit = {},
    viewModel: DebtDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var repaying by remember { mutableStateOf(false) }
    var confirmBad by remember { mutableStateOf(false) }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }
    LaunchedEffect(state.closed) { if (state.closed) onBack() }

    val debt = state.debt
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("借贷详情") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回") }
                },
                actions = {
                    IconButton(onClick = viewModel::remove, modifier = Modifier.testTag("debt_delete")) {
                        Icon(Icons.Outlined.Delete, contentDescription = "删除")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (debt == null) return@Scaffold
        Column(Modifier.fillMaxSize().padding(padding).testTag("screen_debt_detail")) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(debt.counterparty, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Text(
                            if (debt.direction == DebtDirection.LEND) "借出" else "借入",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("剩余", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        "¥${MoneyFormatter.centsToYuan(debt.remaining)}",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold,
                        color =
                            when {
                                debt.writtenOff -> MaterialTheme.colorScheme.outline
                                state.overdue -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.onSurface
                            },
                        modifier = Modifier.testTag("debt_remaining"),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        buildString {
                            append("本金 ¥")
                            append(MoneyFormatter.centsToYuan(debt.principal))
                            append(" · 已")
                            append(if (debt.direction == DebtDirection.LEND) "收" else "还")
                            append(" ¥")
                            append(MoneyFormatter.centsToYuan(debt.repaid))
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    debt.dueAt?.let {
                        Text(
                            text = "还款日 ${DateUtils.ymdText(DateUtils.toDate(it))}" + if (state.overdue) "（已逾期）" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (state.overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (debt.note.isNotBlank()) {
                        Text(
                            "备注：${debt.note}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (debt.writtenOff) {
                        Text("已标记坏账", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { repaying = true },
                            enabled = !debt.settled && !debt.writtenOff,
                            modifier = Modifier.weight(1f).testTag("debt_repay"),
                        ) { Text("记还款") }
                        TextButton(
                            onClick = { confirmBad = true },
                            enabled = !debt.writtenOff,
                            modifier = Modifier.testTag("debt_bad"),
                        ) { Text("标记坏账") }
                    }
                }
            }

            Text(
                "还款记录",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp),
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            LazyColumn(Modifier.fillMaxSize()) {
                if (state.repayments.isEmpty()) {
                    item {
                        Text(
                            "还没有还款记录",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
                items(state.repayments, key = { it.id }) { txn ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(DateUtils.ymdText(DateUtils.toDate(txn.dateTime)), style = MaterialTheme.typography.bodyMedium)
                            if (txn.note.isNotBlank()) {
                                Text(
                                    txn.note,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Text(
                            "¥${MoneyFormatter.centsToYuan(txn.amount)}",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(start = 16.dp))
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }

    if (repaying) {
        RepayDialog(
            remaining = debt?.remaining ?: 0,
            onDismiss = { repaying = false },
            onConfirm = { text ->
                viewModel.repay(text)
                repaying = false
            },
        )
    }

    if (confirmBad) {
        AlertDialog(
            onDismissRequest = { confirmBad = false },
            title = { Text("标记坏账") },
            text = { Text("确认这笔钱收不回来了？标记后台账会结清，但保留记录。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.markBad()
                        confirmBad = false
                    },
                    modifier = Modifier.testTag("debt_bad_confirm"),
                ) { Text("确认") }
            },
            dismissButton = { TextButton(onClick = { confirmBad = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun RepayDialog(
    remaining: Long,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(MoneyFormatter.centsToPlain(remaining)) }
    val cents = MoneyFormatter.yuanToCents(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("记一笔还款") },
        text = {
            Column {
                Text("剩余 ¥${MoneyFormatter.centsToYuan(remaining)}", style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.filter { c -> c.isDigit() || c == '.' }.take(12) },
                    label = { Text("本次金额（元）") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.testTag("debt_repay_input"),
                )
                Text(
                    text =
                        if (cents != null && cents in 1..remaining) {
                            "还完剩余 ¥${MoneyFormatter.centsToYuan(
                                remaining - cents,
                            )}"
                        } else {
                            "金额需在 1 到剩余之间"
                        },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text) },
                enabled = cents != null && cents in 1..remaining,
                modifier = Modifier.testTag("debt_repay_confirm"),
            ) { Text("确认") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
