package com.xxjz.ledger.feature.report

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xxjz.ledger.core.common.MoneyFormatter
import com.xxjz.ledger.core.designsystem.component.EmptyState
import com.xxjz.ledger.core.designsystem.theme.LocalAmountColors
import com.xxjz.ledger.core.ui.chart.BarChart
import com.xxjz.ledger.core.ui.chart.ChartBox
import com.xxjz.ledger.core.ui.chart.ChartMath
import com.xxjz.ledger.core.ui.chart.EmptyRing
import com.xxjz.ledger.core.ui.chart.LineChart
import com.xxjz.ledger.core.ui.chart.PieChart
import com.xxjz.ledger.core.ui.chart.PieSlice
import com.xxjz.ledger.core.ui.chart.toArgbLong
import com.xxjz.ledger.domain.model.TxnType

/**
 * P07 报表首页（AC-35 ~ AC-37 / AC-40）。
 *
 * @param onDrill 分类下钻 P08
 * @param onYearSummary 年度总结 P09
 */
@Composable
fun ReportScreen(
    onDrill: (String) -> Unit = {},
    onYearSummary: () -> Unit = {},
    viewModel: ReportViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val summary = state.summary
    var selectedSlice by remember { mutableIntStateOf(-1) }

    Column(Modifier.fillMaxSize().testTag("screen_report")) {
        WindowHeader(
            label = state.label,
            onPrevious = viewModel::previous,
            onNext = viewModel::next,
            onYearSummary = onYearSummary,
        )
        Spacer(Modifier.height(4.dp))
        CapsuleRow(
            labels = ReportPeriod.entries.map { it.label },
            selectedIndex = ReportPeriod.entries.indexOf(state.window.period),
            onSelect = { viewModel.setPeriod(ReportPeriod.entries[it]) },
        )
        Spacer(Modifier.height(8.dp))
        CapsuleRow(
            labels = listOf("支出", "收入"),
            selectedIndex = if (state.type == TxnType.EXPENSE) 0 else 1,
            onSelect = { viewModel.setType(if (it == 0) TxnType.EXPENSE else TxnType.INCOME) },
        )
        Spacer(Modifier.height(10.dp))
        TotalsCard(summary = summary, isExpenseTab = state.type == TxnType.EXPENSE)
        Spacer(Modifier.height(10.dp))
        CapsuleRow(
            labels = ReportView.entries.map { it.label },
            selectedIndex = ReportView.entries.indexOf(state.view),
            onSelect = { viewModel.setView(ReportView.entries[it]) },
        )
        Spacer(Modifier.height(8.dp))

        if (!summary.hasData) {
            EmptyState(
                title = "这段时间还没有${if (state.type == TxnType.EXPENSE) "支出" else "收入"}记录",
                description = "记几笔账后，这里会有消费构成与趋势",
                modifier = Modifier.fillMaxSize(),
            )
            return@Column
        }

        when (state.view) {
            ReportView.PIE ->
                PieSection(
                    ranks = summary.ranks,
                    total = if (state.type == TxnType.EXPENSE) summary.expense else summary.income,
                    selected = selectedSlice,
                    onSelect = { index -> selectedSlice = if (selectedSlice == index) -1 else index },
                    onDrill = { index -> summary.ranks.getOrNull(index)?.let { onDrill(it.id) } },
                )
            ReportView.RANK ->
                RankList(
                    items = summary.ranks,
                    onSelect = { onDrill(it.id) },
                    modifier = Modifier.fillMaxSize(),
                )
            ReportView.TREND -> TrendSection(summary = summary, period = state.window.period)
        }
    }
}

@Composable
private fun WindowHeader(
    label: String,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onYearSummary: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPrevious, modifier = Modifier.testTag("report_prev")) {
            Icon(Icons.Outlined.ChevronLeft, contentDescription = "上一周期")
        }
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f).testTag("report_label"),
        )
        IconButton(onClick = onNext, modifier = Modifier.testTag("report_next")) {
            Icon(Icons.Outlined.ChevronRight, contentDescription = "下一周期")
        }
        IconButton(onClick = onYearSummary, modifier = Modifier.testTag("year_summary_entry")) {
            Icon(Icons.Outlined.Insights, contentDescription = "年度总结")
        }
    }
}

/** 收支总览卡：大额 + 环比 + 笔数 + 日均 + 结余。 */
@Composable
private fun TotalsCard(
    summary: ReportSummary,
    isExpenseTab: Boolean,
) {
    val colors = LocalAmountColors.current
    val amount = if (isExpenseTab) summary.expense else summary.income
    val change = if (isExpenseTab) summary.expenseChange else summary.incomeChange
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("report_totals"),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (isExpenseTab) "总支出" else "总收入",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                ChangeLabel(percent = change, expenseMode = isExpenseTab)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = "¥${MoneyFormatter.centsToYuan(amount)}",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = if (isExpenseTab) colors.expense else colors.income,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                MiniStat(label = "笔数", value = "${summary.count}")
                MiniStat(label = "日均", value = "¥${MoneyFormatter.centsToYuan(summary.dailyAverage)}")
                MiniStat(label = "结余", value = "¥${MoneyFormatter.centsToYuan(summary.balance)}")
            }
        }
    }
}

@Composable
private fun MiniStat(
    label: String,
    value: String,
) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

/** 饼图 + 中心文案 + 下方联动排行。 */
@Composable
private fun PieSection(
    ranks: List<RankItem>,
    total: Long,
    selected: Int,
    onSelect: (Int) -> Unit,
    onDrill: (Int) -> Unit,
) {
    val slices =
        ranks.mapIndexed { index, item ->
            PieSlice(label = item.name, value = item.amount, color = rankColor(index).toArgbLong())
        }
    val active = ranks.getOrNull(selected)
    Column(Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (slices.isEmpty()) {
                EmptyRing(modifier = Modifier.size(180.dp))
            } else {
                PieChart(
                    slices = slices,
                    modifier = Modifier.size(200.dp),
                    selected = selected,
                    onSelect = onSelect,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = active?.name ?: "合计",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "¥${MoneyFormatter.centsToYuan(active?.amount ?: total)}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (active != null) {
                            Text(
                                text = "${active.percent}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        RankList(
            items = ranks,
            onSelect = { item -> onDrill(ranks.indexOf(item)) },
            selectedId = active?.id,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** 趋势：月/周按日折线，年按月双柱。 */
@Composable
private fun TrendSection(
    summary: ReportSummary,
    period: ReportPeriod,
) {
    val colors = LocalAmountColors.current
    var selected by remember { mutableIntStateOf(-1) }
    val points = summary.trend
    val chartPoints = ChartMath.points(points.map { it.label }, points.map { it.expense })

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Legend(color = colors.expense, text = "支出")
            if (period == ReportPeriod.YEAR) Legend(color = colors.income, text = "收入")
        }
        Spacer(Modifier.height(8.dp))
        val hint =
            if (selected in points.indices) {
                val p = points[selected]
                "${p.label} · 支出 ¥${MoneyFormatter.centsToYuan(p.expense)} · 收入 ¥${MoneyFormatter.centsToYuan(p.income)}"
            } else {
                "点击图表查看具体金额"
            }
        Text(
            text = hint,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("trend_hint"),
        )
        Spacer(Modifier.height(8.dp))
        ChartBox(height = 200.dp) {
            if (period == ReportPeriod.YEAR) {
                BarChart(
                    points = chartPoints,
                    modifier = Modifier.fillMaxSize(),
                    barColor = colors.expense,
                    secondaryPoints = ChartMath.points(points.map { it.label }, points.map { it.income }),
                    secondaryColor = colors.income,
                    selectedIndex = selected,
                    onPick = { selected = it },
                )
            } else {
                LineChart(
                    points = chartPoints,
                    modifier = Modifier.fillMaxSize(),
                    lineColor = colors.expense,
                    fillColor = colors.expense.copy(alpha = 0.14f),
                    selectedIndex = selected,
                    onPick = { selected = it },
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        AxisLabels(points.map { it.label }, period)
        Spacer(Modifier.height(16.dp))
        summary.peak?.let { peak ->
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "支出最高：${peak.label} ¥${MoneyFormatter.centsToYuan(peak.expense)}",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** X 轴标签：天多时只显示首/三分点/末，避免糊成一团。 */
@Composable
private fun AxisLabels(
    labels: List<String>,
    period: ReportPeriod,
) {
    if (labels.isEmpty()) return
    val picks =
        when {
            period == ReportPeriod.YEAR -> labels.indices.toList()
            labels.size <= 8 -> labels.indices.toList()
            else -> listOf(0, labels.size / 3, labels.size * 2 / 3, labels.lastIndex)
        }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        picks.forEach { index ->
            Text(
                text = labels[index],
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Legend(
    color: Color,
    text: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Spacer(Modifier.width(4.dp))
        Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
