package com.xxjz.ledger.feature.ledger

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xxjz.ledger.core.common.MoneyFormatter
import com.xxjz.ledger.core.designsystem.component.AmountDirection
import com.xxjz.ledger.core.designsystem.component.AmountText
import com.xxjz.ledger.core.designsystem.component.EmptyState
import com.xxjz.ledger.domain.model.Template
import com.xxjz.ledger.domain.model.TxnType

/** P01 明细首页：月小计 + 按天分组流水（M2/M4 范围）。 */
@Composable
fun LedgerScreen(
    onAdd: () -> Unit = {},
    onOpen: (String) -> Unit = {},
    onUseTemplate: (String) -> Unit = {},
    onOpenSearch: () -> Unit = {},
    onOpenCalendar: () -> Unit = {},
    onOpenBackup: () -> Unit = {},
    viewModel: LedgerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var showMonthPicker by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.messages.collect { snackbar.showSnackbar(it) } }

    if (showMonthPicker) {
        MonthPickerDialog(
            current = java.time.YearMonth.of(state.monthKey / 100, state.monthKey % 100),
            onPick = { ym ->
                viewModel.selectMonth(ym.year, ym.monthValue)
                showMonthPicker = false
            },
            onDismiss = { showMonthPicker = false },
        )
    }

    Box(Modifier.fillMaxSize().testTag("screen_ledger")) {
        Column(Modifier.fillMaxSize()) {
            MonthHeader(
                label = state.monthLabel,
                onPrevious = viewModel::previousMonth,
                onNext = viewModel::nextMonth,
                onClickLabel = { showMonthPicker = true },
                onSearch = onOpenSearch,
                onCalendar = onOpenCalendar,
            )
            state.backupHint?.let { hint ->
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.secondaryContainer)
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                            .testTag("home_backup_hint"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        hint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onOpenBackup, modifier = Modifier.testTag("home_backup_action")) {
                        Text("去备份")
                    }
                }
            }
            SummaryCard(income = state.income, expense = state.expense)
            if (state.expense > 0) {
                SummaryHint(elapsedDays = state.elapsedDays, dailyAverage = state.dailyAverage)
            }
            if (state.templates.isNotEmpty()) {
                TemplateBar(templates = state.templates, onUse = onUseTemplate)
            }
            if (state.groups.isEmpty() && !state.loading) {
                EmptyState(
                    title = "这个月还没有账目",
                    description = "点击下方 ＋ 记下第一笔",
                    actionText = "记一笔",
                    onAction = onAdd,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 96.dp),
                ) {
                    state.groups.forEach { group ->
                        item(key = "head_${group.dateKey}") { DayHeader(group) }
                        items(group.rows, key = { it.txn.id }) { row ->
                            TxnItem(row) { onOpen(row.txn.id) }
                        }
                    }
                }
            }
        }
        SnackbarHost(hostState = snackbar, modifier = Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun MonthHeader(
    label: String,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onClickLabel: () -> Unit,
    onSearch: () -> Unit,
    onCalendar: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPrevious, modifier = Modifier.testTag("month_prev")) {
            Icon(Icons.Outlined.ChevronLeft, contentDescription = "上个月")
        }
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f).clickable(onClick = onClickLabel).testTag("month_label"),
        )
        IconButton(onClick = onNext, modifier = Modifier.testTag("month_next")) {
            Icon(Icons.Outlined.ChevronRight, contentDescription = "下个月")
        }
        IconButton(onClick = onCalendar, modifier = Modifier.testTag("calendar_entry")) {
            Icon(Icons.Outlined.CalendarMonth, contentDescription = "日历")
        }
        IconButton(onClick = onSearch, modifier = Modifier.testTag("search_entry")) {
            Icon(Icons.Outlined.Search, contentDescription = "搜索")
        }
    }
}

/** 小结条：已过天数与日均支出，3 秒后自动消失（M4-T3）。 */
@Composable
private fun SummaryHint(
    elapsedDays: Int,
    dailyAverage: Long,
) {
    var visible by remember { mutableStateOf(true) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(3_000)
        visible = false
    }
    androidx.compose.animation.AnimatedVisibility(visible = visible) {
        Text(
            text = "本月已过 $elapsedDays 天 · 日均支出 ¥${MoneyFormatter.centsToYuan(dailyAverage)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 20.dp, bottom = 4.dp).testTag("summary_hint"),
        )
    }
}

/** 模板快捷区：点一下把金额/备注带进记账面板（AC-13）。 */
@Composable
private fun TemplateBar(
    templates: List<Template>,
    onUse: (String) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().testTag("template_bar"),
    ) {
        items(templates, key = { it.id }) { template ->
            Surface(
                onClick = { onUse(template.id) },
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Text(
                    text = "${template.name} ¥${MoneyFormatter.centsToPlain(template.amount)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun SummaryCard(
    income: Long,
    expense: Long,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "本月支出",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "¥${MoneyFormatter.centsToYuan(expense)}",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.testTag("month_expense"),
            )
            Spacer(Modifier.height(12.dp))
            Row {
                SummaryCell("收入", income, AmountDirection.INCOME, Modifier.weight(1f))
                SummaryCell("结余", income - expense, AmountDirection.NEUTRAL, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun SummaryCell(
    title: String,
    amount: Long,
    direction: AmountDirection,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text(title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
        AmountText(
            cents = amount,
            direction = direction,
            signed = false,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun DayHeader(group: DayGroup) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = group.title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = group.subtitle,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text =
                buildString {
                    if (group.expense > 0) append("支 ${MoneyFormatter.centsToYuan(group.expense)}")
                    if (group.income > 0) {
                        if (isNotEmpty()) append("  ")
                        append("收 ${MoneyFormatter.centsToYuan(group.income)}")
                    }
                },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TxnItem(
    row: TxnRow,
    onClick: () -> Unit,
) {
    val txn = row.txn
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .size(38.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = row.categoryName.take(1),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(row.categoryName, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                if (txn.excludedFromStats) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "不计入",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            val sub =
                buildString {
                    if (txn.note.isNotBlank()) append(txn.note)
                    if (txn.type == TxnType.TRANSFER) {
                        if (isNotEmpty()) append(" · ")
                        append("转账")
                    }
                }
            if (sub.isNotEmpty()) {
                Text(
                    text = sub,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        AmountText(
            cents = txn.amount,
            direction =
                when (txn.type) {
                    TxnType.INCOME -> AmountDirection.INCOME
                    TxnType.EXPENSE -> AmountDirection.EXPENSE
                    else -> AmountDirection.NEUTRAL
                },
            signed = txn.type != TxnType.TRANSFER,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}
