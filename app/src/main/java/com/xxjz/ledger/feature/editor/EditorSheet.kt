package com.xxjz.ledger.feature.editor

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xxjz.ledger.core.common.DateUtils
import com.xxjz.ledger.core.designsystem.component.ConfirmDialog
import com.xxjz.ledger.domain.model.TxnType

/**
 * 记账面板（AC-01）：半屏底部弹层，含类型切换、分类/账户/日期、金额计算器键盘与备注。
 *
 * 数据全部来自 [EditorViewModel] 的单一 UiState，面板本身不持有业务状态。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorSheet(
    viewModel: EditorViewModel,
    editTxnId: String? = null,
    templateId: String? = null,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showDiscard by remember { mutableStateOf(false) }
    var showCategory by remember { mutableStateOf(false) }
    var showAccount by remember { mutableStateOf(false) }
    var showToAccount by remember { mutableStateOf(false) }
    var showDate by remember { mutableStateOf(false) }

    LaunchedEffect(editTxnId) {
        if (editTxnId != null) viewModel.loadForEdit(editTxnId)
    }
    LaunchedEffect(templateId) {
        if (templateId != null) viewModel.loadTemplate(templateId)
    }
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            if (event is EditorEvent.Saved) onSaved()
        }
    }

    ModalBottomSheet(
        onDismissRequest = {
            if (state.dirty) showDiscard = true else onDismiss()
        },
        sheetState = sheetState,
        modifier = Modifier.testTag("editor_sheet"),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            TypeTabs(
                current = state.type,
                onSelect = viewModel::setType,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.type == TxnType.EXPENSE || state.type == TxnType.INCOME) {
                    PickerChip(
                        label = state.categoryName ?: "选分类",
                        tag = "chip_category",
                        onClick = { showCategory = true },
                    )
                }
                PickerChip(
                    label =
                        when (state.type) {
                            TxnType.TRANSFER -> "从 ${state.accountName ?: "账户"}"
                            else -> state.accountName ?: "选账户"
                        },
                    tag = "chip_account",
                    onClick = { showAccount = true },
                )
                if (state.type == TxnType.TRANSFER) {
                    PickerChip(
                        label = "到 ${state.toAccountName ?: "账户"}",
                        tag = "chip_to_account",
                        onClick = { showToAccount = true },
                    )
                }
                PickerChip(
                    label = friendlyDate(state.dateTime),
                    tag = "chip_date",
                    onClick = { showDate = true },
                )
            }

            Spacer(Modifier.height(16.dp))
            Text(
                text = state.calc.expressionText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.End,
            )
            Text(
                text = if (state.calc.error != null) state.calc.error!! else "¥ " + state.amountDisplay,
                style = MaterialTheme.typography.headlineLarge.copy(fontSize = 40.sp),
                fontWeight = FontWeight.SemiBold,
                color =
                    when {
                        state.calc.error != null -> MaterialTheme.colorScheme.error
                        state.type == TxnType.INCOME -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                modifier = Modifier.fillMaxWidth().testTag("amount_display"),
                textAlign = TextAlign.End,
            )

            if (state.type == TxnType.TRANSFER) {
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = state.feeText,
                    onValueChange = viewModel::setFee,
                    label = { Text("手续费") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("fee_input"),
                )
            }

            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = state.note,
                onValueChange = viewModel::setNote,
                placeholder = { Text("添加备注…") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("note_input"),
            )

            state.error?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(10.dp))
            Keypad(
                onKey = { key -> handleKey(viewModel, key) },
                onBackspace = viewModel::backspace,
                onBackspaceLong = {
                    repeat(8) { viewModel.backspace() }
                },
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = viewModel::equals,
                enabled = !state.saving,
                modifier = Modifier.fillMaxWidth().height(52.dp).testTag("key_equals"),
            ) {
                Text(
                    if (state.calc.evaluated) {
                        if (state.editingId != null) "保存修改" else "保存"
                    } else {
                        "="
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Spacer(Modifier.height(20.dp))
        }
    }

    if (showDiscard) {
        ConfirmDialog(
            title = "放弃这笔记账？",
            message = "已填写的内容不会保存。",
            confirmText = "放弃",
            dismissText = "继续记账",
            destructive = true,
            onConfirm = {
                showDiscard = false
                onDismiss()
            },
            onDismiss = { showDiscard = false },
        )
    }

    if (showCategory) {
        CategoryPickerDialog(
            tree = state.tree,
            selectedId = state.categoryId,
            onSelect = {
                viewModel.selectCategory(it)
                showCategory = false
            },
            onDismiss = { showCategory = false },
        )
    }

    if (showAccount) {
        AccountPickerDialog(
            accounts = state.accounts,
            selectedId = state.accountId,
            title = if (state.type == TxnType.TRANSFER) "转出账户" else "选择账户",
            onSelect = {
                viewModel.selectAccount(it)
                showAccount = false
            },
            onDismiss = { showAccount = false },
        )
    }

    if (showToAccount) {
        AccountPickerDialog(
            accounts = state.accounts,
            selectedId = state.toAccountId,
            title = "转入账户",
            onSelect = {
                viewModel.selectToAccount(it)
                showToAccount = false
            },
            onDismiss = { showToAccount = false },
        )
    }

    if (showDate) {
        EditorDatePickerDialog(
            initialMillis = state.dateTime,
            onPick = {
                viewModel.setDateTime(it)
                showDate = false
            },
            onDismiss = { showDate = false },
        )
    }
}

private fun friendlyDate(millis: Long): String {
    val date = DateUtils.toDate(millis)
    val base = DateUtils.friendlyDate(date)
    return if (base == "今天" || base == "昨天" || base == "前天") base else "${date.monthValue}/${date.dayOfMonth}"
}

private fun handleKey(
    viewModel: EditorViewModel,
    key: String,
) {
    when (key) {
        "+" -> viewModel.operator(CalcOp.ADD)
        "-" -> viewModel.operator(CalcOp.SUB)
        "×" -> viewModel.operator(CalcOp.MUL)
        "÷" -> viewModel.operator(CalcOp.DIV)
        "." -> viewModel.dot()
        else -> key.firstOrNull()?.takeIf { it.isDigit() }?.let { viewModel.digit(it) }
    }
}

@Composable
private fun TypeTabs(
    current: TxnType,
    onSelect: (TxnType) -> Unit,
) {
    val tabs =
        listOf(
            TxnType.EXPENSE to "支出",
            TxnType.INCOME to "收入",
            TxnType.TRANSFER to "转账",
        )
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        tabs.forEach { (type, label) ->
            val selected = type == current
            Surface(
                onClick = { onSelect(type) },
                shape = RoundedCornerShape(50),
                color =
                    if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                modifier = Modifier.weight(1f).testTag("type_${type.name.lowercase()}"),
            ) {
                Text(
                    text = label,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelLarge,
                    color =
                        if (selected) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    modifier = Modifier.padding(vertical = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun PickerChip(
    label: String,
    tag: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.testTag(tag),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Keypad(
    onKey: (String) -> Unit,
    onBackspace: () -> Unit,
    onBackspaceLong: () -> Unit,
) {
    val rows =
        listOf(
            listOf("7", "8", "9", "⌫"),
            listOf("4", "5", "6", "+"),
            listOf("1", "2", "3", "-"),
            listOf(".", "0", "×", "÷"),
        )
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { key ->
                    val isOp = key in setOf("+", "-", "×", "÷")
                    val isBack = key == "⌫"
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color =
                            when {
                                isOp -> MaterialTheme.colorScheme.secondaryContainer
                                isBack -> MaterialTheme.colorScheme.surfaceVariant
                                else -> MaterialTheme.colorScheme.surface
                            },
                        tonalElevation = if (isOp) 0.dp else 1.dp,
                        modifier =
                            Modifier
                                .weight(1f)
                                .height(54.dp)
                                .testTag("key_$key")
                                .combinedClickable(
                                    onClick = { if (isBack) onBackspace() else onKey(key) },
                                    onLongClick = { if (isBack) onBackspaceLong() },
                                ),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().height(54.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = key,
                                style = MaterialTheme.typography.titleLarge,
                                color =
                                    if (isOp) {
                                        MaterialTheme.colorScheme.onSecondaryContainer
                                    } else {
                                        Color.Unspecified
                                    },
                            )
                        }
                    }
                }
            }
        }
    }
}
