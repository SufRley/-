package com.xxjz.ledger.feature.split

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xxjz.ledger.core.common.MoneyFormatter
import com.xxjz.ledger.core.designsystem.component.CompactHeader
import com.xxjz.ledger.core.designsystem.component.ConfirmDialog
import com.xxjz.ledger.domain.model.Account
import com.xxjz.ledger.feature.editor.AccountPickerDialog
import com.xxjz.ledger.feature.editor.accountGlyphOf

/**
 * 拆分明细页（v1.9.5 第 8 条）。
 *
 * 独立页面而不是弹层：明细可能加到 20 行，弹层里滚列表加键盘会把手势和输入法都搅在一起。
 *
 * 页面只做三件事：加行删行、给每行选账户与填金额、合计对上才放行保存。
 */
@Composable
fun SplitTxnScreen(
    onBack: () -> Unit,
    onDone: (String) -> Unit,
    viewModel: SplitTxnViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var picking by remember { mutableStateOf<String?>(null) }
    var confirmExit by remember { mutableStateOf(false) }

    // 改到一半按返回会丢掉整页输入，先问一句（AC-7.9）
    val attemptExit = {
        if (state.dirty) confirmExit = true else onBack()
    }
    BackHandler(enabled = state.dirty && !confirmExit) { attemptExit() }

    if (confirmExit) {
        ConfirmDialog(
            title = "放弃本次拆分？",
            message = "已填的明细不会保存。",
            confirmText = "放弃",
            destructive = true,
            onConfirm = {
                confirmExit = false
                onBack()
            },
            onDismiss = { confirmExit = false },
        )
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { message -> onDone(message) }
    }

    if (picking != null) {
        AccountPickerDialog(
            accounts = state.accounts,
            selectedId = state.rows.firstOrNull { it.key == picking }?.accountId,
            title = "选择账户",
            onSelect = { accountId ->
                picking?.let { viewModel.setAccount(it, accountId) }
                picking = null
            },
            onDismiss = { picking = null },
        )
    }

    Column(Modifier.fillMaxSize().testTag("screen_split")) {
        CompactHeader(
            title = "拆分明细",
            leading = {
                IconButton(onClick = attemptExit, modifier = Modifier.testTag("split_back")) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                }
            },
        )
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            SourceSummary(state)
            Spacer(Modifier.height(12.dp))
            state.rows.forEachIndexed { index, row ->
                SplitRowEditor(
                    index = index,
                    row = row,
                    account = state.accounts.firstOrNull { it.id == row.accountId },
                    onPickAccount = { picking = row.key },
                    onAmount = { viewModel.setAmount(row.key, it) },
                    onRemove = { viewModel.removeRow(row.key) },
                    removable = state.canRemove,
                )
                Spacer(Modifier.height(8.dp))
            }
            TextButton(
                onClick = { viewModel.addRow() },
                enabled = state.canAdd,
                modifier = Modifier.testTag("split_add"),
            ) {
                Text(if (state.canAdd) "＋ 增加明细" else "最多 20 条")
            }
            state.error?.let {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("split_error"),
                )
            }
            Spacer(Modifier.height(16.dp))
        }
        SplitFooter(state = state, onSave = viewModel::save)
    }
}

/** 顶部原账摘要：一行说清「在拆哪一笔」。 */
@Composable
private fun SourceSummary(state: SplitUiState) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = state.categoryName.ifBlank { "原账目" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "¥${MoneyFormatter.centsToYuan(state.sourceCents)}",
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

/** 一行明细：账户（可点换）+ 金额输入 + 删除。 */
@Composable
private fun SplitRowEditor(
    index: Int,
    row: SplitRow,
    account: Account?,
    onPickAccount: () -> Unit,
    onAmount: (String) -> Unit,
    onRemove: () -> Unit,
    removable: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth().testTag("split_row_$index"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Surface(
            onClick = onPickAccount,
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.weight(1f).testTag("split_account_$index"),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = accountGlyphOf(account?.type),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = account?.name ?: "选择账户",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
            }
        }
        OutlinedTextField(
            value = row.amountText,
            onValueChange = onAmount,
            placeholder = { Text("0.00") },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.width(112.dp).height(52.dp).testTag("split_amount_$index"),
        )
        IconButton(
            onClick = onRemove,
            enabled = removable,
            modifier = Modifier.testTag("split_remove_$index"),
        ) {
            Icon(Icons.Outlined.Close, contentDescription = "删除这一条")
        }
    }
}

/** 底部合计条：差额实时提示，合计对上才允许保存。 */
@Composable
private fun SplitFooter(
    state: SplitUiState,
    onSave: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "合计 ¥${MoneyFormatter.centsToYuan(state.totalCents)}",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.testTag("split_total"),
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = diffLabel(state),
                    style = MaterialTheme.typography.labelMedium,
                    color =
                        if (state.diffCents == 0L) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    modifier = Modifier.testTag("split_diff"),
                )
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onSave,
                enabled = state.blocker == null,
                modifier = Modifier.fillMaxWidth().height(48.dp).testTag("split_save"),
            ) {
                Text("保存拆分")
            }
        }
    }
}

/** 差额文案：对齐时给个明确的「与原始金额一致」。 */
private fun diffLabel(state: SplitUiState): String =
    when {
        state.diffCents == 0L -> "与原始金额一致"
        state.diffCents > 0 -> "超出 ¥${MoneyFormatter.centsToYuan(state.diffCents)}"
        else -> "还差 ¥${MoneyFormatter.centsToYuan(-state.diffCents)}"
    }
