package com.xxjz.ledger.feature.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
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
import com.xxjz.ledger.core.designsystem.component.AmountDirection
import com.xxjz.ledger.core.designsystem.component.AmountText
import com.xxjz.ledger.core.designsystem.component.ConfirmDialog
import com.xxjz.ledger.domain.model.TxnType

/** P02 流水详情：大金额 + 字段清单 + 编辑/退款/复制/不计入/删除。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TxnDetailScreen(
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
    viewModel: TxnDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var showRefund by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var needClose by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is DetailEvent.Closed -> needClose = true
                is DetailEvent.Message -> snackbar.showSnackbar(event.text)
            }
        }
    }
    LaunchedEffect(needClose) {
        if (needClose) onBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("账目详情") },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("detail_back")) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val txn = state.txn
        if (txn == null) {
            if (!state.loading) {
                Text("记录不存在或已删除", modifier = Modifier.padding(padding).padding(24.dp))
            }
            return@Scaffold
        }
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
        ) {
            Spacer(Modifier.height(24.dp))
            Text(
                text =
                    if (txn.type == TxnType.INCOME) {
                        "收入"
                    } else if (txn.type == TxnType.EXPENSE) {
                        "支出"
                    } else {
                        "转账"
                    },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
            AmountText(
                cents = txn.amount,
                direction =
                    when (txn.type) {
                        TxnType.INCOME -> AmountDirection.INCOME
                        TxnType.EXPENSE -> AmountDirection.EXPENSE
                        else -> AmountDirection.NEUTRAL
                    },
                signed = txn.type != TxnType.TRANSFER,
                style = MaterialTheme.typography.displaySmall,
                modifier = Modifier.fillMaxWidth().testTag("detail_amount"),
            )
            if (state.refunded > 0) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "已退款 ¥${MoneyFormatter.centsToYuan(state.refunded)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(20.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    InfoRow("分类", if (txn.type == TxnType.TRANSFER) "转账" else state.categoryName)
                    InfoRow("账户", state.accountName.ifEmpty { "—" })
                    state.toAccountName?.let { InfoRow("转入账户", it) }
                    InfoRow("时间", DateUtils.toDateTime(txn.dateTime).format(DateUtils.displayFormatter))
                    if (txn.note.isNotBlank()) InfoRow("备注", txn.note)
                    InfoRow("计入收支", if (txn.excludedFromStats) "否" else "是")
                    if (txn.fee > 0) InfoRow("手续费", "¥${MoneyFormatter.centsToYuan(txn.fee)}")
                }
            }

            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton("编辑", "detail_edit") { onEdit(txn.id) }
                if (txn.type == TxnType.EXPENSE) {
                    ActionButton("退款", "detail_refund") { showRefund = true }
                }
                ActionButton("复制", "detail_copy") { viewModel.duplicate() }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton(
                    if (txn.excludedFromStats) "恢复计入" else "不计入收支",
                    "detail_exclude",
                ) { viewModel.toggleExclude() }
                ActionButton("删除", "detail_delete", destructive = true) { showDelete = true }
            }
            Spacer(Modifier.height(40.dp))
        }
    }

    if (showRefund) {
        RefundDialog(
            maxCents = state.refundable,
            onDismiss = { showRefund = false },
            onConfirm = {
                showRefund = false
                viewModel.refund(it)
            },
        )
    }

    if (showDelete) {
        ConfirmDialog(
            title = "删除这笔账目？",
            message = "删除后可在回收站找回（默认保留 30 天）。",
            confirmText = "删除",
            destructive = true,
            onConfirm = {
                showDelete = false
                viewModel.delete()
            },
            onDismiss = { showDelete = false },
        )
    }
}

@Composable
private fun InfoRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ActionButton(
    label: String,
    tag: String,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.testTag(tag),
    ) {
        Text(
            label,
            color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun RefundDialog(
    maxCents: Long,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit,
) {
    var text by remember { mutableStateOf(MoneyFormatter.centsToPlain(maxCents)) }
    val cents = MoneyFormatter.yuanToCents(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("退款") },
        text = {
            Column {
                Text(
                    "最多可退 ¥${MoneyFormatter.centsToYuan(maxCents)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { input ->
                        text = input.filter { it.isDigit() || it == '.' }.take(12)
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth().testTag("refund_amount"),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { cents?.let(onConfirm) },
                enabled = cents != null && cents > 0,
                modifier = Modifier.testTag("refund_confirm"),
            ) { Text("确认退款") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
