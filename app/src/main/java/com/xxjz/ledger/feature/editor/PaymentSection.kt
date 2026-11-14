package com.xxjz.ledger.feature.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.xxjz.ledger.core.common.MoneyFormatter
import com.xxjz.ledger.domain.model.Account
import com.xxjz.ledger.domain.model.DebtDirection
import com.xxjz.ledger.domain.model.TxnType

/** 组合模式下一个账户金额输入框的宽度：够打五位数，又给账户名留出位置。 */
private val SplitInputWidth = 108.dp

/**
 * 付款账户区（PRD v1.9.1 第 5 条）。
 *
 * 一行解决三件事：左边「付款账户」、右侧当前账户、末尾「单选 / 组合」切换。
 * - 单选：点右侧账户名拉起账户选择弹窗。
 * - 组合：展开成一组账户行，每个账户后面就是一个金额输入框；
 *   填了钱的账户会在上方浮出一枚可 × 掉的标签，一眼看清「谁出了多少」。
 */
@Composable
internal fun PaymentSection(
    state: EditorUiState,
    viewModel: EditorViewModel,
    onPickAccount: () -> Unit,
    onPickToAccount: () -> Unit,
    onPickDebtAccount: () -> Unit,
    onNewDebt: () -> Unit,
) {
    // 只有收支能做组合：转账是「一进一出」，借贷是「一借一还」，拆开就成了两笔错账
    val splitAvailable = state.type == TxnType.EXPENSE || state.type == TxnType.INCOME

    Column(Modifier.fillMaxWidth()) {
        if (state.type == TxnType.DEBT) {
            DebtDirectionRow(current = state.debtDirection, onSelect = viewModel::setDebtDirection)
            Spacer(Modifier.height(8.dp))
            AccountLine(
                label = "对方",
                value = state.debtAccountName ?: "选择借贷账户",
                tag = "row_debt_account",
                onClick = onPickDebtAccount,
            )
            if (state.debtAccounts.isEmpty()) {
                TextButton(onClick = onNewDebt, modifier = Modifier.testTag("btn_new_debt_account")) {
                    Text("＋ 新建借贷账户")
                }
            }
            Spacer(Modifier.height(6.dp))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AccountLine(
                label = if (state.type == TxnType.DEBT) "资金账户" else "付款账户",
                value =
                    state.accountName ?: when (state.type) {
                        TxnType.TRANSFER -> "选择转出账户"
                        TxnType.DEBT -> "选择资金账户"
                        else -> "选择账户"
                    },
                tag = "row_account",
                onClick = onPickAccount,
                modifier = Modifier.weight(1f),
            )
            if (splitAvailable) {
                Spacer(Modifier.width(8.dp))
                SplitModeSwitch(split = state.splitMode, onChange = viewModel::setSplitMode)
            }
        }

        if (state.type == TxnType.TRANSFER) {
            Spacer(Modifier.height(8.dp))
            AccountLine(
                label = "转入账户",
                value = state.toAccountName ?: "选择转入账户",
                tag = "row_to_account",
                onClick = onPickToAccount,
            )
            FeeField(value = state.feeText, onChange = viewModel::setFee)
        }

        if (splitAvailable && state.splitMode) {
            Spacer(Modifier.height(8.dp))
            SplitPanel(state = state, viewModel = viewModel)
        }

        Spacer(Modifier.height(8.dp))
        DateChip(state = state, onDateChange = viewModel::setDateTime)
    }
}

/** 组合分摊面板：上方是可 × 的已填标签，下方是逐个账户的金额输入。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SplitPanel(
    state: EditorUiState,
    viewModel: EditorViewModel,
) {
    if (state.splits.isNotEmpty()) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth().testTag("split_chips"),
        ) {
            state.splits.forEach { (accountId, cents) ->
                val name = state.accounts.firstOrNull { it.id == accountId }?.name ?: "账户"
                SplitChip(
                    text = "$name ¥${MoneyFormatter.centsToYuan(cents)}",
                    onRemove = { viewModel.removeSplit(accountId) },
                    tag = "split_chip_$accountId",
                )
            }
        }
        Spacer(Modifier.height(6.dp))
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        state.fundAccounts.forEach { account ->
            SplitAccountRow(
                account = account,
                value = state.splits[account.id]?.let { MoneyFormatter.centsToPlain(it) }.orEmpty(),
                onChange = { viewModel.setSplitAmount(account.id, it) },
            )
        }
    }
    Spacer(Modifier.height(6.dp))
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "合计 ¥${MoneyFormatter.centsToYuan(state.splitTotal)}",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.testTag("split_total"),
        )
        Spacer(Modifier.weight(1f))
        if (state.splits.size < 2) {
            Text(
                text = "至少填两个账户",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 一枚可 × 掉的分摊标签，表示「这个账户出了这么多钱」。 */
@Composable
private fun SplitChip(
    text: String,
    onRemove: () -> Unit,
    tag: String,
) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.testTag(tag),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 10.dp, end = 2.dp),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            IconButton(onClick = onRemove, modifier = Modifier.size(24.dp).testTag("${tag}_x")) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "移除",
                    modifier = Modifier.size(13.dp),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}

/** 组合模式下单个账户一行：名称 + 该账户出多少钱。 */
@Composable
private fun SplitAccountRow(
    account: Account,
    value: String,
    onChange: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = account.name,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
        OutlinedTextField(
            value = value,
            onValueChange = { raw -> onChange(raw.filter { it.isDigit() || it == '.' }.take(12)) },
            placeholder = { Text("0.00") },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.width(SplitInputWidth).height(52.dp).testTag("split_input_${account.id}"),
        )
    }
}

/** 「付款账户」一行：左边标签、右边当前账户 + 小箭头暗示可点。 */
@Composable
private fun AccountLine(
    label: String,
    value: String,
    tag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = modifier.fillMaxWidth().testTag(tag),
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color =
                    if (value.startsWith("选择")) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                maxLines = 1,
            )
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 单选 / 组合切换。
 *
 * 做成胶囊里的两段而不是一个 Switch：一个开关说不清「组合」是什么意思，
 * 两个词摆在那里更直白，也顺带告诉用户这功能存在。
 */
@Composable
private fun SplitModeSwitch(
    split: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.testTag("split_switch"),
    ) {
        Row(Modifier.padding(2.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Segment(label = "单选", selected = !split, tag = "mode_single") { onChange(false) }
            Segment(label = "组合", selected = split, tag = "mode_split") { onChange(true) }
        }
    }
}

@Composable
private fun Segment(
    label: String,
    selected: Boolean,
    tag: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = if (selected) MaterialTheme.colorScheme.surface else Color.Transparent,
        modifier = Modifier.testTag(tag),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color =
                if (selected) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

/** 借贷方向：我借出 / 我借入。 */
@Composable
private fun DebtDirectionRow(
    current: DebtDirection,
    onSelect: (DebtDirection) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        DebtDirection.entries.forEach { direction ->
            val selected = direction == current
            val label = if (direction == DebtDirection.LEND) "我借出" else "我借入"
            Surface(
                onClick = { onSelect(direction) },
                shape = RoundedCornerShape(50),
                color =
                    if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                modifier = Modifier.weight(1f).testTag("debt_dir_${direction.name.lowercase()}"),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color =
                        if (selected) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** 日期胶囊：点了弹日期选择器。 */
@Composable
private fun DateChip(
    state: EditorUiState,
    onDateChange: (Long) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Surface(
        onClick = { open = true },
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.testTag("chip_date"),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(5.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
            Spacer(Modifier.size(6.dp))
            Text(
                text = friendlyDate(state.dateTime),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (open) {
        EditorDatePickerDialog(
            initialMillis = state.dateTime,
            onPick = {
                onDateChange(it)
                open = false
            },
            onDismiss = { open = false },
        )
    }
}
