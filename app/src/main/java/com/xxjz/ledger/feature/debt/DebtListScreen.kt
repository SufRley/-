package com.xxjz.ledger.feature.debt

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xxjz.ledger.core.common.DateUtils
import com.xxjz.ledger.core.common.MoneyFormatter
import com.xxjz.ledger.core.designsystem.component.EmptyState
import com.xxjz.ledger.domain.model.Debt
import com.xxjz.ledger.domain.model.DebtDirection
import java.time.Instant
import java.time.LocalDate

/** P18 借贷台账（AC-63 / AC-66 / AC-67）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebtListScreen(
    onBack: () -> Unit = {},
    onOpenDetail: (String) -> Unit = {},
    viewModel: DebtListViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var creating by remember { mutableStateOf<DebtDirection?>(null) }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("借贷台账") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            FloatingActionButton(onClick = { creating = DebtDirection.LEND }, modifier = Modifier.testTag("fab_add_debt")) {
                Icon(Icons.Filled.Add, contentDescription = "新建借贷")
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).testTag("screen_debt")) {
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SummaryCard("应收", state.group.receivable, Modifier.weight(1f))
                SummaryCard("应付", state.group.payable, Modifier.weight(1f))
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            LazyColumn(Modifier.fillMaxSize()) {
                if (state.group.empty) {
                    item {
                        EmptyState(
                            title = "还没有借贷记录",
                            description = "借出、借入分开记，还了多少一目了然",
                            modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
                            actionText = "记一笔借出",
                            onAction = { creating = DebtDirection.LEND },
                        )
                    }
                }
                if (state.group.lend.isNotEmpty()) {
                    item { GroupTitle("借出（对方欠我）") }
                    items(state.group.lend, key = { it.id }) { debt ->
                        DebtRow(debt, onClick = { onOpenDetail(debt.id) })
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(start = 16.dp))
                    }
                }
                if (state.group.borrow.isNotEmpty()) {
                    item { GroupTitle("借入（我欠对方）") }
                    items(state.group.borrow, key = { it.id }) { debt ->
                        DebtRow(debt, onClick = { onOpenDetail(debt.id) })
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(start = 16.dp))
                    }
                }
                if (state.group.settled.isNotEmpty()) {
                    item {
                        TextButton(
                            onClick = viewModel::toggleSettled,
                            modifier = Modifier.padding(start = 8.dp).testTag("debt_toggle_settled"),
                        ) {
                            Text(if (state.showSettled) "收起已结清（${state.group.settled.size}）" else "展开已结清（${state.group.settled.size}）")
                        }
                    }
                    if (state.showSettled) {
                        items(state.group.settled, key = { "settled-${it.id}" }) { debt ->
                            DebtRow(debt, onClick = { onOpenDetail(debt.id) }, dimmed = true)
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(start = 16.dp))
                        }
                    }
                }
                item { Spacer(Modifier.height(88.dp)) }
            }
        }
    }

    creating?.let { direction ->
        NewDebtDialog(
            direction = direction,
            onSwitch = { creating = it },
            onDismiss = { creating = null },
            onConfirm = { counterparty, amount, due, note ->
                viewModel.create(direction, counterparty, amount, due, note)
                creating = null
            },
        )
    }
}

@Composable
private fun SummaryCard(
    label: String,
    amount: Long,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(12.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            "¥${MoneyFormatter.centsToYuan(amount)}",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun GroupTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun DebtRow(
    debt: Debt,
    onClick: () -> Unit,
    dimmed: Boolean = false,
) {
    val overdue = debt.isOverdue()
    Column(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp).testTag("debt_row_${debt.id}"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                debt.counterparty,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = if (dimmed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                "¥${MoneyFormatter.centsToYuan(debt.remaining)}",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color =
                    when {
                        debt.writtenOff -> MaterialTheme.colorScheme.outline
                        overdue -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurface
                    },
            )
        }
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            val sub =
                buildString {
                    append("本金 ¥")
                    append(MoneyFormatter.centsToYuan(debt.principal))
                    append(" · 已")
                    append(if (debt.direction == DebtDirection.LEND) "收" else "还")
                    append(" ¥")
                    append(MoneyFormatter.centsToYuan(debt.repaid))
                    debt.dueAt?.let {
                        append(" · ")
                        append(DateUtils.ymdText(DateUtils.toDate(it)))
                    }
                }
            Text(
                sub,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            when {
                debt.writtenOff -> Tag("坏账", MaterialTheme.colorScheme.outline)
                overdue -> Tag("逾期", MaterialTheme.colorScheme.error)
                debt.settled -> Tag("已结清", MaterialTheme.colorScheme.outline)
            }
        }
    }
}

@Composable
private fun Tag(
    text: String,
    color: androidx.compose.ui.graphics.Color,
) {
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewDebtDialog(
    direction: DebtDirection,
    onSwitch: (DebtDirection) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (counterparty: String, amount: String, due: LocalDate?, note: String) -> Unit,
) {
    var counterparty by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var due by remember { mutableStateOf<LocalDate?>(null) }
    var showPicker by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (direction == DebtDirection.LEND) "记一笔借出" else "记一笔借入") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DebtDirection.entries.forEach { d ->
                        FilterChip(
                            selected = direction == d,
                            onClick = { onSwitch(d) },
                            label = { Text(if (d == DebtDirection.LEND) "借出" else "借入") },
                            modifier = Modifier.testTag("debt_dir_${d.name}"),
                        )
                    }
                }
                OutlinedTextField(
                    value = counterparty,
                    onValueChange = { counterparty = it.take(12) },
                    label = { Text("对方") },
                    singleLine = true,
                    modifier = Modifier.testTag("debt_counterparty"),
                )
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it.filter { c -> c.isDigit() || c == '.' }.take(12) },
                    label = { Text("本金（元）") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.testTag("debt_amount"),
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it.take(20) },
                    label = { Text("备注") },
                    singleLine = true,
                    modifier = Modifier.testTag("debt_note"),
                )
                TextButton(onClick = { showPicker = true }, modifier = Modifier.testTag("debt_due")) {
                    Text(due?.let { "还款日 ${DateUtils.ymdText(it)}" } ?: "设置还款日（可选）")
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(counterparty, amount, due, note) },
                modifier = Modifier.testTag("debt_confirm"),
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )

    if (showPicker) {
        val picker = rememberDatePickerState(initialSelectedDateMillis = DateUtils.startOfDayMillis(LocalDate.now()))
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    picker.selectedDateMillis?.let { due = Instant.ofEpochMilli(it).atZone(DateUtils.zone).toLocalDate() }
                    showPicker = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { showPicker = false }) { Text("取消") } },
        ) { DatePicker(state = picker) }
    }
}
