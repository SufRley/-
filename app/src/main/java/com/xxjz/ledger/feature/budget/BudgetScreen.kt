package com.xxjz.ledger.feature.budget

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.xxjz.ledger.core.common.MoneyFormatter
import com.xxjz.ledger.core.designsystem.component.EmptyState
import com.xxjz.ledger.feature.ledger.MonthPickerDialog
import java.time.YearMonth

/**
 * P10 预算首页（AC-44 ~ AC-47）。
 *
 * 结构：总预算卡 → 结余目标卡 → 分类预算列表（超支置顶、按使用比例排序）。
 */
@Composable
fun BudgetScreen(
    onEdit: (budgetId: String?, monthKey: Int) -> Unit = { _, _ -> },
    viewModel: BudgetViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showMonthPicker by remember { mutableStateOf(false) }
    var showGoalDialog by remember { mutableStateOf(false) }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick = { onEdit(null, state.monthKey) },
                modifier = Modifier.testTag("fab_add_budget"),
            ) {
                Icon(Icons.Filled.Add, contentDescription = "新建预算")
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).testTag("screen_budget")) {
            BudgetHeader(
                label = state.monthLabel,
                onPrev = viewModel::previousMonth,
                onNext = viewModel::nextMonth,
                onPickMonth = { showMonthPicker = true },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                val inheritedFrom = state.inheritedFrom
                if (inheritedFrom != null) {
                    item {
                        Text(
                            text = "本月未单独设置，沿用 ${BudgetViewModel.monthLabel(inheritedFrom)} 的配置",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }

                if (!state.hasConfig) {
                    item {
                        EmptyState(
                            title = "还没有预算",
                            description = "设定月度预算，随时掌握还能花多少",
                            modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
                        )
                    }
                } else {
                    state.total?.let { total ->
                        item { TotalBudgetCard(total, state.monthExpense) }
                    }
                    item {
                        SavingGoalCard(
                            goal = state.savingGoalCents,
                            balance = state.monthBalance,
                            fraction = state.savingFraction,
                            achieved = state.savingAchieved,
                            onSetGoal = { showGoalDialog = true },
                        )
                    }
                    if (state.categoryBudgets.isNotEmpty()) {
                        item {
                            Text(
                                text = "分类预算",
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                            )
                        }
                        items(state.categoryBudgets, key = { it.budget.id }) { item ->
                            BudgetRow(
                                progress = item,
                                onClick = { onEdit(item.budget.id, state.monthKey) },
                            )
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant,
                                modifier = Modifier.padding(start = 16.dp),
                            )
                        }
                    }
                }

                item {
                    Text(
                        text = "统计口径：已用金额只计入支出，排除「不计入收支」的账目；选中大类会连同其下小类一起统计。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                item { Spacer(Modifier.height(72.dp)) }
            }
        }
    }

    if (showMonthPicker) {
        MonthPickerDialog(
            current = YearMonth.of(state.monthKey / 100, state.monthKey % 100),
            onPick = { ym ->
                viewModel.selectMonth(ym.year * 100 + ym.monthValue)
                showMonthPicker = false
            },
            onDismiss = { showMonthPicker = false },
        )
    }

    if (showGoalDialog) {
        SavingGoalDialog(
            current = state.savingGoalCents,
            onConfirm = { cents ->
                viewModel.setSavingGoal(cents)
                showGoalDialog = false
            },
            onDismiss = { showGoalDialog = false },
        )
    }
}

@Composable
private fun BudgetHeader(
    label: String,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onPickMonth: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "预算",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(start = 8.dp).weight(1f),
        )
        IconButton(onClick = onPrev, modifier = Modifier.testTag("budget_month_prev")) {
            Icon(Icons.Outlined.ChevronLeft, contentDescription = "上月")
        }
        TextButton(onClick = onPickMonth, modifier = Modifier.testTag("budget_month_pick")) {
            Text(label)
        }
        IconButton(onClick = onNext, modifier = Modifier.testTag("budget_month_next")) {
            Icon(Icons.Outlined.ChevronRight, contentDescription = "下月")
        }
    }
}

/** 总预算卡：本月已用 / 总额 + 进度 + 剩余 + 日均可花。 */
@Composable
private fun TotalBudgetCard(
    progress: BudgetProgress,
    monthExpense: Long,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(16.dp).testTag("card_total_budget"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("本月预算", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = "¥${MoneyFormatter.centsToYuan(progress.used)}",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                    color =
                        if (progress.overspent) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                )
                Text(
                    text = " / ¥${MoneyFormatter.centsToYuan(progress.budget.amount)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            Spacer(Modifier.height(10.dp))
            BudgetBar(progress, height = 8)
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    text = remainingText(progress),
                    style = MaterialTheme.typography.labelLarge,
                    color =
                        if (progress.overspent) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                )
                dailyText(progress)?.let {
                    Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = "本月支出合计 ¥${MoneyFormatter.centsToYuan(monthExpense)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

/** 结余目标卡（AC-48）：本月结余 ÷ 目标。 */
@Composable
private fun SavingGoalCard(
    goal: Long,
    balance: Long,
    fraction: Float,
    achieved: Boolean,
    onSetGoal: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("card_saving_goal"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("结余目标", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = onSetGoal, modifier = Modifier.testTag("btn_set_goal")) {
                    Text(if (goal <= 0) "设置目标" else "修改")
                }
            }
            if (goal <= 0) {
                Text(
                    text = "设一个目标，看看每月能留下多少",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = "¥${MoneyFormatter.centsToYuan(balance)}",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color =
                            if (balance < 0) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                    )
                    Text(
                        text = " / ¥${MoneyFormatter.centsToYuan(goal)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 2.dp),
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = if (achieved) "已达成" else "${(fraction * 100).toInt()}%",
                        style = MaterialTheme.typography.labelMedium,
                        color =
                            if (achieved) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        modifier = Modifier.testTag("goal_percent"),
                    )
                }
                Spacer(Modifier.height(8.dp))
                GoalBar(fraction, achieved)
            }
        }
    }
}

/** 结余目标进度条。 */
@Composable
private fun GoalBar(
    fraction: Float,
    achieved: Boolean,
) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(percent = 50))
                .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(6.dp)
                .clip(RoundedCornerShape(percent = 50))
                .background(
                    if (achieved) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.secondary
                    },
                ),
        )
    }
}

/** 结余目标输入弹窗。 */
@Composable
private fun SavingGoalDialog(
    current: Long,
    onConfirm: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(if (current > 0) MoneyFormatter.centsToPlain(current) else "") }
    val cents = MoneyFormatter.yuanToCents(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("结余目标") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.filter { c -> c.isDigit() || c == '.' }.take(12) },
                    label = { Text("每月结余目标（元）") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.testTag("goal_input"),
                )
                Text(
                    text = "目标按「本月收入 - 本月支出」计算",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(cents ?: 0L) },
                modifier = Modifier.testTag("goal_confirm"),
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
