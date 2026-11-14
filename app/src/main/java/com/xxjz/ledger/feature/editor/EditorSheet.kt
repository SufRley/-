package com.xxjz.ledger.feature.editor

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.Handshake
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xxjz.ledger.core.common.DateUtils
import com.xxjz.ledger.domain.model.AccountType
import com.xxjz.ledger.domain.model.DebtDirection
import com.xxjz.ledger.domain.model.TxnType

/**
 * 记账面板（AC-01）：半屏底部弹层，含类型切换、分类/账户/日期、金额计算器键盘与备注。
 *
 * 数据全部来自 [EditorViewModel] 的单一 UiState，面板本身不持有业务状态。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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
    var showCategory by remember { mutableStateOf(false) }
    var showAccount by remember { mutableStateOf(false) }
    var showToAccount by remember { mutableStateOf(false) }
    var showDate by remember { mutableStateOf(false) }
    var showDebtAccount by remember { mutableStateOf(false) }
    var showNewDebt by remember { mutableStateOf(false) }
    var newDebtName by remember { mutableStateOf("") }

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
        // 滑下即关闭，不再弹「放弃这笔记账？」（v1.8.1 #6）：记一笔账是很轻的动作，
        // 每次退出都拦一道确认比误触丢内容更烦人。保存按钮常驻后误触概率也降低了。
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.testTag("editor_sheet"),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            TypeTabs(
                current = state.type,
                onSelect = viewModel::setType,
            )
            Spacer(Modifier.height(12.dp))
            // 转出/转入/日期在 360dp 屏上放不进一行，改成自动换行（v1.8.1 #4）
            if (state.type == TxnType.DEBT) {
                DebtDirectionSwitch(
                    current = state.debtDirection,
                    onSelect = viewModel::setDebtDirection,
                )
                Spacer(Modifier.height(8.dp))
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.type == TxnType.EXPENSE || state.type == TxnType.INCOME) {
                    PickerChip(
                        label = state.categoryName ?: "选分类",
                        tag = "chip_category",
                        onClick = { showCategory = true },
                    )
                }
                if (state.type == TxnType.DEBT) {
                    PickerChip(
                        label = state.debtAccountName ?: "选对方",
                        tag = "chip_debt_account",
                        icon = Icons.Outlined.Handshake,
                        onClick = { showDebtAccount = true },
                    )
                }
                PickerChip(
                    label =
                        when (state.type) {
                            TxnType.TRANSFER -> "从 ${state.accountName ?: "账户"}"
                            TxnType.DEBT ->
                                if (state.debtDirection == DebtDirection.LEND) {
                                    "从 ${state.accountName ?: "资金账户"}"
                                } else {
                                    "到 ${state.accountName ?: "资金账户"}"
                                }
                            else -> state.accountName ?: "选账户"
                        },
                    tag = "chip_account",
                    // 带上账户类型图标，让用户一眼看出这是账户选择器而不是一个静态标签
                    icon = accountIconOf(state.accounts.firstOrNull { it.id == state.accountId }?.type),
                    onClick = { showAccount = true },
                )
                if (state.type == TxnType.TRANSFER) {
                    PickerChip(
                        label = "到 ${state.toAccountName ?: "账户"}",
                        tag = "chip_to_account",
                        icon = accountIconOf(state.accounts.firstOrNull { it.id == state.toAccountId }?.type),
                        onClick = { showToAccount = true },
                    )
                }
                PickerChip(
                    label = friendlyDate(state.dateTime),
                    tag = "chip_date",
                    onClick = { showDate = true },
                )
            }
            if (state.type == TxnType.DEBT && state.debtAccounts.isEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "还没有借贷账户，先建一个人的账户再记账",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(
                    onClick = {
                        newDebtName = ""
                        showNewDebt = true
                    },
                    modifier = Modifier.testTag("btn_new_debt_account"),
                ) {
                    Text("＋ 新建借贷账户")
                }
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
                // 保存不再需要先按「=」：未求值时自动求值再存（v1.8.1 #5）
                onClick = viewModel::submitOrEvaluate,
                enabled = !state.saving,
                modifier = Modifier.fillMaxWidth().height(52.dp).testTag("btn_save"),
            ) {
                Text(
                    if (state.editingId != null) "保存修改" else "保存",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Spacer(Modifier.height(20.dp))
        }
    }

    EditorPickDialogs(
        viewModel = viewModel,
        state = state,
        show =
            PickVisibility(
                category = showCategory,
                account = showAccount,
                toAccount = showToAccount,
                debtAccount = showDebtAccount,
                newDebt = showNewDebt,
                date = showDate,
            ),
        newDebtName = newDebtName,
        onNewDebtNameChange = { newDebtName = it },
        close = { which ->
            when (which) {
                Pick.CATEGORY -> showCategory = false
                Pick.ACCOUNT -> showAccount = false
                Pick.TO_ACCOUNT -> showToAccount = false
                Pick.DEBT_ACCOUNT -> showDebtAccount = false
                Pick.NEW_DEBT -> showNewDebt = false
                Pick.DATE -> showDate = false
            }
        },
    )
}

/** 六个选择器弹窗的显隐状态，打包传给 [EditorPickDialogs]，免得参数一长串。 */
private data class PickVisibility(
    val category: Boolean,
    val account: Boolean,
    val toAccount: Boolean,
    val debtAccount: Boolean,
    val newDebt: Boolean,
    val date: Boolean,
)

/** 要关掉哪个弹窗。 */
private enum class Pick { CATEGORY, ACCOUNT, TO_ACCOUNT, DEBT_ACCOUNT, NEW_DEBT, DATE }

/**
 * 记账面板的所有选择器弹窗（v1.9.0 抽出）。
 *
 * 抽出来的原因很实在：拆分前 `EditorSheet` 已经 242 行、圈复杂度 41，都压着 detekt 阈值，
 * 再往里面加功能就会失控。这些弹窗与面板主体没有数据耦合，只依赖 UiState。
 */
@Composable
private fun EditorPickDialogs(
    viewModel: EditorViewModel,
    state: EditorUiState,
    show: PickVisibility,
    newDebtName: String,
    onNewDebtNameChange: (String) -> Unit,
    close: (Pick) -> Unit,
) {
    if (show.category) {
        CategoryPickerDialog(
            tree = state.tree,
            selectedId = state.categoryId,
            onSelect = {
                viewModel.selectCategory(it)
                close(Pick.CATEGORY)
            },
            onDismiss = { close(Pick.CATEGORY) },
        )
    }

    if (show.account) {
        AccountPickerDialog(
            // 借贷类型下资金账户不能是借贷账户本身
            accounts = if (state.type == TxnType.DEBT) state.fundAccounts else state.accounts,
            selectedId = state.accountId,
            title =
                when (state.type) {
                    TxnType.TRANSFER -> "转出账户"
                    TxnType.DEBT -> "资金账户"
                    else -> "选择账户"
                },
            onSelect = {
                viewModel.selectAccount(it)
                close(Pick.ACCOUNT)
            },
            onDismiss = { close(Pick.ACCOUNT) },
        )
    }

    if (show.toAccount) {
        AccountPickerDialog(
            accounts = state.accounts,
            selectedId = state.toAccountId,
            title = "转入账户",
            onSelect = {
                viewModel.selectToAccount(it)
                close(Pick.TO_ACCOUNT)
            },
            onDismiss = { close(Pick.TO_ACCOUNT) },
        )
    }

    if (show.debtAccount) {
        AccountPickerDialog(
            accounts = state.debtAccounts,
            selectedId = state.debtAccountId,
            title = "选择对方账户",
            onSelect = {
                viewModel.selectDebtAccount(it)
                close(Pick.DEBT_ACCOUNT)
            },
            onDismiss = { close(Pick.DEBT_ACCOUNT) },
        )
    }

    if (show.newDebt) {
        NewDebtAccountDialog(
            value = newDebtName,
            onValueChange = onNewDebtNameChange,
            onConfirm = {
                viewModel.createDebtAccount(newDebtName)
                close(Pick.NEW_DEBT)
            },
            onDismiss = { close(Pick.NEW_DEBT) },
        )
    }

    if (show.date) {
        EditorDatePickerDialog(
            initialMillis = state.dateTime,
            onPick = {
                viewModel.setDateTime(it)
                close(Pick.DATE)
            },
            onDismiss = { close(Pick.DATE) },
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
            // v1.9.0：借贷从「独立台账」搬进记账面板（PRD 2.5）
            TxnType.DEBT to "借贷",
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
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.testTag(tag),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 账户类型对应的图标；未选账户时给一个中性图标，别让 chip 看起来是纯文字标签。 */
private fun accountIconOf(type: AccountType?): ImageVector =
    when (type) {
        AccountType.CASH -> Icons.Outlined.Payments
        AccountType.SAVINGS -> Icons.Outlined.AccountBalance
        AccountType.CREDIT -> Icons.Outlined.CreditCard
        AccountType.DEBT -> Icons.Outlined.Handshake
        AccountType.INVEST -> Icons.AutoMirrored.Outlined.TrendingUp
        null -> Icons.Outlined.AccountBalanceWallet
    }

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Keypad(
    onKey: (String) -> Unit,
    onBackspace: () -> Unit,
    onBackspaceLong: () -> Unit,
) {
    // 数字占左三列，运算符独立占最右一列（v1.8.1 #5）：以前运算符散落在数字行里，
    // 底部那个大按钮还兼着「=」和「保存」两个身份，用户以为必须先算一次。
    val rows =
        listOf(
            listOf("7", "8", "9", "+"),
            listOf("4", "5", "6", "-"),
            listOf("1", "2", "3", "×"),
            listOf(".", "0", "⌫", "÷"),
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

/**
 * 借贷动作切换（v1.9.0 净值模型）：只决定这一笔是借出还是借入，
 * 不再限制能挑哪个人的账户——同一个人借出去的钱和欠下的钱记在同一个账户上。
 */
@Composable
private fun DebtDirectionSwitch(
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
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                )
            }
        }
    }
}

/** 记账面板内一键新建借贷账户（PRD 8.3）：只填人名，本金在金额区填。 */
@Composable
private fun NewDebtAccountDialog(
    value: String,
    onValueChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(20.dp)) {
                Text(
                    text = "新建借贷账户",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = value,
                    onValueChange = { onValueChange(it.take(20)) },
                    label = { Text("对方姓名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("new_debt_name"),
                )
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    TextButton(onClick = onConfirm, modifier = Modifier.testTag("new_debt_confirm")) { Text("新建") }
                }
            }
        }
    }
}
