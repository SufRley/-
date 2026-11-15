package com.xxjz.ledger.feature.reimbursement

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
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
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xxjz.ledger.core.common.DateUtils
import com.xxjz.ledger.core.common.MoneyFormatter
import com.xxjz.ledger.core.designsystem.component.CompactHeader
import com.xxjz.ledger.core.designsystem.component.EmptyState
import com.xxjz.ledger.domain.model.Account
import com.xxjz.ledger.domain.usecase.FundingAccounts
import com.xxjz.ledger.feature.editor.EditorDatePickerDialog
import java.time.LocalTime

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
    // 展开状态是**会话级**的：默认全部折叠，离开页面再回来恢复默认（PRD 6.2）
    var expanded by remember { mutableStateOf(emptySet<Int>()) }
    val groups = remember(state.pending) { pendingGroupsOf(state.pending) }

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
                    groups.forEach { group ->
                        item(key = "ph_${group.dateKey}") {
                            PendingGroupHeader(
                                group = group,
                                expanded = group.dateKey in expanded,
                                onToggle = {
                                    expanded =
                                        if (group.dateKey in expanded) expanded - group.dateKey else expanded + group.dateKey
                                },
                            )
                        }
                        // 折叠时不渲染条目，而不是用透明度藏起来：几十条待报销的列表没必要量一遍高度
                        if (group.dateKey in expanded) {
                            items(group.rows, key = { it.txn.id }) { row ->
                                ReimburseRow(row) { confirming = row }
                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outlineVariant,
                                    modifier = Modifier.padding(start = 16.dp),
                                )
                            }
                        }
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
            accounts = state.fundingAccounts,
            onDismiss = { confirming = null },
            onConfirm = { text, accountId, dateTime ->
                viewModel.confirm(row.txn.id, text, accountId = accountId, dateTime = dateTime)
                confirming = null
            },
        )
    }
}

/**
 * 待报销的组头（v1.9.4 第 5 条）：日期 · N 笔 + 组合计 + 折叠箭头，整行可点。
 *
 * 默认折叠时用户先看到的是一份「哪几天有账没报」的概览，
 * 而不是一屏铺开的条目——这也是需求方指定的默认态。
 */
@Composable
private fun PendingGroupHeader(
    group: PendingGroup,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .testTag("reimburse_group_${group.dateKey}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "${group.dateLabel} · ${group.rows.size} 笔",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
        Text(
            "¥${MoneyFormatter.centsToYuan(group.total)}",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Icon(
            imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
            contentDescription = if (expanded) "收起" else "展开",
            modifier = Modifier.size(20.dp).padding(start = 4.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
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

/**
 * 确认报销对话框（v1.9.4 第 2 条）。
 *
 * 三项新选择：到账账户、报销日期、报销时间。日期与时间合成一个 timestamp，
 * 秒归零——报销是「哪一天到账」，精确到分钟就够，留着秒只会让同一天的记录排序变得莫名其妙。
 */
@Composable
private fun ReimburseDialog(
    row: ReimbursementRow,
    accounts: List<Account>,
    onDismiss: () -> Unit,
    onConfirm: (text: String, accountId: String?, dateTime: Long) -> Unit,
) {
    var text by remember { mutableStateOf(MoneyFormatter.centsToPlain(row.txn.amount)) }
    // 默认到账账户 = 原支出的付款账户；它被删/被隐藏时不预选，由用户自己挑（不替用户决定钱去哪）
    var accountId by remember {
        mutableStateOf(FundingAccounts.resolveDefault(accounts, row.txn.accountId)?.id)
    }
    var dateTime by remember { mutableStateOf(System.currentTimeMillis()) }
    var showAccountPicker by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }

    val cents = MoneyFormatter.yuanToCents(text)
    val amountValid = cents != null && cents in 1..row.txn.amount
    val account = accounts.firstOrNull { it.id == accountId }
    val accountMissing = account == null && accounts.isNotEmpty()
    val valid = amountValid && account != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("确认报销") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "支出 ¥${MoneyFormatter.centsToYuan(row.txn.amount)}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                AccountField(
                    account = account,
                    fallback = if (accounts.isEmpty()) "没有可收款的资金账户" else "请选择到账账户",
                    onOpen = { showAccountPicker = true },
                )
                if (accountMissing) {
                    Text(
                        "原账户已不可用，请选择到账账户",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                DateTimeFields(
                    dateTime = dateTime,
                    onPickDate = { showDatePicker = true },
                    onPickTime = { showTimePicker = true },
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
                        if (amountValid) {
                            "自付 ¥${MoneyFormatter.centsToYuan(row.txn.amount - (cents ?: 0L))}"
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
                onClick = { onConfirm(text, accountId, dateTime) },
                enabled = valid,
                modifier = Modifier.testTag("reimburse_confirm"),
            ) { Text("确认") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )

    if (showAccountPicker) {
        AccountPickerSheet(
            accounts = accounts,
            current = accountId,
            onPick = {
                accountId = it
                showAccountPicker = false
            },
            onDismiss = { showAccountPicker = false },
        )
    }
    if (showDatePicker) {
        EditorDatePickerDialog(
            initialMillis = dateTime,
            onPick = { dateMillis ->
                dateTime = combineDateTime(dateMillis, DateUtils.toDateTime(dateTime).toLocalTime())
                showDatePicker = false
            },
            onDismiss = { showDatePicker = false },
        )
    }
    if (showTimePicker) {
        HistoryTimePickerDialog(
            initial = DateUtils.toDateTime(dateTime).toLocalTime(),
            onPick = {
                dateTime = combineDateTime(dateTime, it)
                showTimePicker = false
            },
            onDismiss = { showTimePicker = false },
        )
    }
}

/**
 * 到账账户行：账户名 + 余额，右侧箭头。
 *
 * 把余额显示出来是有用的：报销到账后余额会变，用户确认前能对一眼「是不是这张卡」。
 */
@Composable
private fun AccountField(
    account: Account?,
    fallback: String,
    onOpen: () -> Unit,
) {
    Surface(
        onClick = onOpen,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth().testTag("reimburse_account"),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "到账账户",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    account?.let { "${it.name} · ¥${MoneyFormatter.centsToYuan(it.balance)}" } ?: fallback,
                    style = MaterialTheme.typography.bodyMedium,
                    color =
                        if (account == null) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                )
            }
            Icon(
                Icons.Outlined.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 日期与时间两个并排的小按钮。 */
@Composable
private fun DateTimeFields(
    dateTime: Long,
    onPickDate: () -> Unit,
    onPickTime: () -> Unit,
) {
    val date = DateUtils.toDate(dateTime)
    val time = DateUtils.toDateTime(dateTime).toLocalTime()
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PickerChip("报销日期", DateUtils.friendlyDate(date), "reimburse_date", onPickDate, Modifier.weight(1f))
        PickerChip("报销时间", "%02d:%02d".format(time.hour, time.minute), "reimburse_time", onPickTime, Modifier.weight(1f))
    }
}

@Composable
private fun PickerChip(
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
        modifier = modifier.testTag(tag),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** 到账账户选择：只列资金账户（现金 / 储蓄卡 / 投资）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccountPickerSheet(
    accounts: List<Account>,
    current: String?,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag("reimburse_account_sheet")) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(
                "到账账户",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(start = 20.dp, bottom = 8.dp),
            )
            if (accounts.isEmpty()) {
                Text(
                    "没有可收款的资金账户，请先到「账户」里添加一个现金或储蓄卡账户。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
            accounts.forEach { account ->
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPick(account.id) }
                            .padding(horizontal = 20.dp, vertical = 12.dp)
                            .testTag("reimburse_account_${account.id}"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(account.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "¥${MoneyFormatter.centsToYuan(account.balance)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (account.id == current) {
                        Icon(
                            Icons.Outlined.Check,
                            contentDescription = "当前选择",
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

/** 时间选择：M3 的 TimePicker 需要自己套一层 Dialog 与确认按钮。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistoryTimePickerDialog(
    initial: LocalTime,
    onPick: (LocalTime) -> Unit,
    onDismiss: () -> Unit,
) {
    val state = rememberTimePickerState(initialHour = initial.hour, initialMinute = initial.minute, is24Hour = true)
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                TimePicker(state = state)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    TextButton(
                        onClick = { onPick(LocalTime.of(state.hour, state.minute)) },
                        modifier = Modifier.testTag("reimburse_time_confirm"),
                    ) { Text("确定") }
                }
            }
        }
    }
}

/**
 * 把「选中的日期」与「选中的时刻」合成一个时间戳，秒与毫秒归零。
 *
 * 两次选择是独立的：用户先改日期再改时间，或反过来，都要保留另一半。
 * 日期可能来自 M3 的 `DatePicker`（UTC 零点）也可能来自既有时间戳，
 * 所以统一走 [DateUtils.toDate] 取日历日，避开时区偏移把日期挪一天。
 */
internal fun combineDateTime(
    dateMillis: Long,
    time: LocalTime,
): Long = DateUtils.toMillis(DateUtils.toDate(dateMillis), time.withSecond(0).withNano(0))
