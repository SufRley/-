package com.xxjz.ledger.feature.recurring

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
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
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
import com.xxjz.ledger.core.common.DateUtils
import com.xxjz.ledger.domain.model.RecurringPeriod
import com.xxjz.ledger.domain.model.TxnType
import com.xxjz.ledger.domain.usecase.RecurringEndMode
import java.time.Instant

/** P17 周期任务编辑（AC-55、AC-56）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecurringEditScreen(
    onBack: () -> Unit = {},
    viewModel: RecurringEditViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showStartPicker by remember { mutableStateOf(false) }
    var showEndPicker by remember { mutableStateOf(false) }

    LaunchedEffect(state.finished) { if (state.finished) onBack() }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(if (state.isNew) "新建周期任务" else "编辑周期任务") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .testTag("screen_recurring_edit"),
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TxnType.entries.filter { it != TxnType.DEBT }.forEach { type ->
                    FilterChip(
                        selected = state.type == type,
                        onClick = { viewModel.setType(type) },
                        label = { Text(type.label()) },
                        modifier = Modifier.testTag("recurring_type_${type.name}"),
                    )
                }
            }
            OutlinedTextField(
                value = state.amountText,
                onValueChange = viewModel::setAmount,
                label = { Text("金额（元）") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth().padding(16.dp).testTag("recurring_amount_input"),
            )
            OutlinedTextField(
                value = state.note,
                onValueChange = viewModel::setNote,
                label = { Text("备注（如：房租）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("recurring_note_input"),
            )

            SectionLabel("周期")
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RecurringPeriod.entries.forEach { period ->
                    FilterChip(
                        selected = state.period == period,
                        onClick = { viewModel.setPeriod(period) },
                        label = { Text(period.label()) },
                        modifier = Modifier.testTag("recurring_period_${period.name}"),
                    )
                }
            }
            OutlinedTextField(
                value = state.intervalText,
                onValueChange = viewModel::setInterval,
                label = { Text("间隔（每 N 个周期）") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth().padding(16.dp).testTag("recurring_interval_input"),
            )

            SectionLabel("开始日期")
            TextButton(
                onClick = { showStartPicker = true },
                modifier = Modifier.padding(horizontal = 8.dp).testTag("recurring_start_date"),
            ) {
                Text("${DateUtils.ymdText(state.startDate)} 起（09:00 执行）")
            }

            SectionLabel("结束条件")
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RecurringEndMode.entries.forEach { mode ->
                    FilterChip(
                        selected = state.endMode == mode,
                        onClick = { viewModel.setEndMode(mode) },
                        label = { Text(mode.label()) },
                        modifier = Modifier.testTag("recurring_end_${mode.name}"),
                    )
                }
            }
            if (state.endMode == RecurringEndMode.COUNT) {
                OutlinedTextField(
                    value = state.maxTimesText,
                    onValueChange = viewModel::setMaxTimes,
                    label = { Text("执行次数") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().padding(16.dp).testTag("recurring_max_times"),
                )
            }
            if (state.endMode == RecurringEndMode.UNTIL) {
                TextButton(
                    onClick = { showEndPicker = true },
                    modifier = Modifier.padding(horizontal = 8.dp).testTag("recurring_end_date"),
                ) {
                    Text(state.endDate?.let { "截止 ${DateUtils.ymdText(it)}" } ?: "选择结束日期")
                }
            }

            SectionLabel("分类")
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                state.categories.take(14).forEach { category ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FilterChip(
                            selected = state.categoryId == category.id,
                            onClick = { viewModel.setCategory(category.id) },
                            label = { Text(category.name) },
                            modifier = Modifier.testTag("recurring_cat_${category.id}"),
                        )
                    }
                }
            }

            SectionLabel("账户")
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                state.accounts.forEach { account ->
                    FilterChip(
                        selected = state.accountId == account.id,
                        onClick = { viewModel.setAccount(account.id) },
                        label = { Text(account.name) },
                        modifier = Modifier.padding(end = 8.dp, bottom = 4.dp).testTag("recurring_acc_${account.id}"),
                    )
                }
            }

            state.error?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(16.dp).testTag("recurring_error"),
                )
            }

            Button(
                onClick = viewModel::save,
                enabled = state.canSave,
                modifier = Modifier.fillMaxWidth().padding(16.dp).testTag("recurring_save"),
            ) { Text("保存") }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(
                text = "到期后会在打开 App 或每日后台任务时自动记账；暂停期间不补账，恢复后从下次执行日重新开始。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(16.dp),
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showStartPicker) {
        val picker = rememberDatePickerState(initialSelectedDateMillis = DateUtils.startOfDayMillis(state.startDate))
        DatePickerDialog(
            onDismissRequest = { showStartPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    picker.selectedDateMillis?.let { viewModel.setStartDate(Instant.ofEpochMilli(it).atZone(DateUtils.zone).toLocalDate()) }
                    showStartPicker = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { showStartPicker = false }) { Text("取消") } },
        ) { DatePicker(state = picker) }
    }

    if (showEndPicker) {
        val picker = rememberDatePickerState(initialSelectedDateMillis = state.endDate?.let { DateUtils.startOfDayMillis(it) })
        DatePickerDialog(
            onDismissRequest = { showEndPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    picker.selectedDateMillis?.let { viewModel.setEndDate(Instant.ofEpochMilli(it).atZone(DateUtils.zone).toLocalDate()) }
                    showEndPicker = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { showEndPicker = false }) { Text("取消") } },
        ) { DatePicker(state = picker) }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

private fun TxnType.label(): String =
    when (this) {
        TxnType.EXPENSE -> "支出"
        TxnType.INCOME -> "收入"
        TxnType.TRANSFER -> "转账"
        TxnType.DEBT -> "借贷"
    }

private fun RecurringPeriod.label(): String =
    when (this) {
        RecurringPeriod.DAY -> "每天"
        RecurringPeriod.WEEK -> "每周"
        RecurringPeriod.MONTH -> "每月"
        RecurringPeriod.YEAR -> "每年"
    }

private fun RecurringEndMode.label(): String =
    when (this) {
        RecurringEndMode.FOREVER -> "永不结束"
        RecurringEndMode.COUNT -> "执行 N 次"
        RecurringEndMode.UNTIL -> "指定日期"
    }
