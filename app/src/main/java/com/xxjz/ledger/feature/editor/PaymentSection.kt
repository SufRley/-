package com.xxjz.ledger.feature.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Handshake
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.xxjz.ledger.core.common.MoneyFormatter
import com.xxjz.ledger.domain.model.Account
import com.xxjz.ledger.domain.model.AccountType
import com.xxjz.ledger.domain.model.DebtDirection
import com.xxjz.ledger.domain.model.TxnType

/** 组合模式下一个账户金额输入框的宽度：够打五位数，又给账户名留出位置。 */
private val SplitInputWidth = 108.dp

/**
 * 付款账户区（PRD v1.9.3 第 6.6 条）。
 *
 * 一整行讲清三件事：左边「付款账户」、右侧当前账户（图标 + 名字）、行尾一个小图标切「单选 / 组合」。
 * 与 v1.9.1 的区别是两个减法：
 * - 「单选 / 组合」的两段文字胶囊收成一个 [Icons.Filled.SwapHoriz] 图标——整行要容得下账户名与金额，
 *   再摆两个汉字胶囊就把名字挤没了；
 * - 日期胶囊从这里搬走，统一进状态胶囊行（[EditorStateChips]），日期/时间/标签挨着排才顺手。
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
            Spacer(Modifier.height(10.dp))
            AccountLine(
                label = "对方",
                value = state.debtAccountName,
                placeholder = "选择借贷账户",
                tag = "row_debt_account",
                onClick = onPickDebtAccount,
                icon = accountGlyphOf(state.debtAccounts.firstOrNull { it.id == state.debtAccountId }?.type),
            )
            if (state.debtAccounts.isEmpty()) {
                TextButton(onClick = onNewDebt, modifier = Modifier.testTag("btn_new_debt_account")) {
                    Text("＋ 新建借贷账户")
                }
            }
            Spacer(Modifier.height(2.dp))
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            AccountLine(
                label = if (state.type == TxnType.DEBT) "资金账户" else "付款账户",
                value = state.accountName,
                placeholder =
                    when (state.type) {
                        TxnType.TRANSFER -> "选择转出账户"
                        TxnType.DEBT -> "选择资金账户"
                        else -> "请选择"
                    },
                tag = "row_account",
                onClick = onPickAccount,
                modifier = Modifier.weight(1f),
                icon = accountGlyphOf(state.accounts.firstOrNull { it.id == state.accountId }?.type),
                trailing =
                    if (splitAvailable) {
                        {
                            SplitModeButton(
                                split = state.splitMode,
                                count = state.splits.size,
                                // 传动作而不是传算好的布尔值：连点时第二个回调也读不到新的 state（v1.9.4 第 3 条）
                                onToggle = viewModel::toggleSplitMode,
                            )
                        }
                    } else {
                        null
                    },
            )
        }

        if (state.type == TxnType.TRANSFER) {
            AccountLine(
                label = "转入账户",
                value = state.toAccountName,
                placeholder = "选择转入账户",
                tag = "row_to_account",
                onClick = onPickToAccount,
                icon = accountGlyphOf(state.accounts.firstOrNull { it.id == state.toAccountId }?.type),
            )
            FeeField(value = state.feeText, onChange = viewModel::setFee)
        }

        if (splitAvailable && state.splitMode) {
            Spacer(Modifier.height(10.dp))
            SplitPanel(state = state, viewModel = viewModel)
        }
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

/**
 * 「付款账户」整行：左标签、右账户（图标 + 名字），行尾可以挂一个组合切换图标。
 *
 * 整行都可点（不是只有账户名可点）——一行里最自然的手势就是把整行当按钮。
 * 未选账户时占位文案用 `error` 色，它是个「必须填但还没填」的字段，不是普通灰提示。
 */
@Composable
private fun AccountLine(
    label: String,
    value: String?,
    placeholder: String,
    tag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        if (value == null) {
            Text(
                text = placeholder,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
                maxLines = 1,
            )
        } else {
            Icon(
                imageVector = icon ?: Icons.Filled.Payments,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
        }
        trailing?.invoke()
    }
}

/**
 * 组合切换：一个 `SwapHoriz` 图标。
 *
 * 图标按钮不抢眼但也不隐藏——旁边账户名后面会追加「等 N 个」，说明这个开关现在是什么状态。
 */
@Composable
private fun SplitModeButton(
    split: Boolean,
    count: Int,
    onToggle: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (split && count > 0) {
            Text(
                text = "等 $count 个",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onToggle, modifier = Modifier.size(36.dp).testTag("split_switch")) {
            Icon(
                imageVector = Icons.Filled.SwapHoriz,
                contentDescription = if (split) "改为单账户支付" else "改为组合支付",
                modifier = Modifier.size(20.dp),
                tint =
                    if (split) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
            )
        }
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

/**
 * 账户类型 → 小图标。
 *
 * 只按类型分，不按账户名猜：账户名是用户随手起的（「Q 卡」「招行」），猜不准反而更乱。
 */
internal fun accountGlyphOf(type: AccountType?): ImageVector =
    when (type) {
        AccountType.CASH -> Icons.Filled.Payments
        AccountType.SAVINGS -> Icons.Filled.AccountBalance
        AccountType.CREDIT -> Icons.Filled.CreditCard
        AccountType.INVEST -> Icons.Filled.TrendingUp
        AccountType.DEBT -> Icons.Filled.Handshake
        null -> Icons.Filled.Payments
    }

/** 转账手续费输入。 */
@Composable
internal fun FeeField(
    value: String,
    onChange: (String) -> Unit,
) {
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text("手续费") },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().testTag("fee_input"),
    )
}
