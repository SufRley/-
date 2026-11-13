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
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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

/**
 * P09 年度总结（AC-42）：12 月双柱、Top3 类别、Top3 商家、年度结余、与去年对比。
 */
@Composable
fun YearSummaryScreen(
    onBack: () -> Unit,
    viewModel: YearSummaryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.load() }
    val colors = LocalAmountColors.current

    Column(Modifier.fillMaxSize().testTag("screen_year_summary")) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, modifier = Modifier.testTag("year_back")) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
            }
            IconButton(onClick = viewModel::previousYear) {
                Icon(Icons.Outlined.ChevronLeft, contentDescription = "上一年")
            }
            Text(
                text = "${state.year} 年度总结",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f).testTag("year_label"),
            )
            IconButton(onClick = viewModel::nextYear) {
                Icon(Icons.Outlined.ChevronRight, contentDescription = "下一年")
            }
        }

        if (!state.hasData) {
            EmptyState(
                title = "${state.year} 年还没有账目",
                description = "记录一整年，年底看这里",
                modifier = Modifier.fillMaxSize(),
            )
            return@Column
        }

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text("全年支出", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                text = "¥${MoneyFormatter.centsToYuan(state.expense)}",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                color = colors.expense,
                            )
                            ChangeLabel(percent = state.expenseChange, expenseMode = true)
                        }
                        Column(Modifier.weight(1f)) {
                            Text("全年收入", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                text = "¥${MoneyFormatter.centsToYuan(state.income)}",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                color = colors.income,
                            )
                            ChangeLabel(percent = ReportMath.changePercent(state.income, state.lastYearIncome), expenseMode = false)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "结余 ¥${MoneyFormatter.centsToYuan(state.balance)}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.testTag("year_balance"),
                    )
                    Text(
                        text = "去年支出 ¥${MoneyFormatter.centsToYuan(
                            state.lastYearExpense,
                        )} · 去年收入 ¥${MoneyFormatter.centsToYuan(state.lastYearIncome)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            SectionHeader("月度收支")
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LegendDot(colors.expense, "支出")
                LegendDot(colors.income, "收入")
            }
            Spacer(Modifier.height(8.dp))
            ChartBox(height = 180.dp) {
                BarChart(
                    points = ChartMath.points(state.months.map { it.label }, state.months.map { it.expense }),
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    barColor = colors.expense,
                    secondaryPoints = ChartMath.points(state.months.map { it.label }, state.months.map { it.income }),
                    secondaryColor = colors.income,
                )
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf("1月", "6月", "12月").forEach {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Spacer(Modifier.height(16.dp))
            SectionHeader("支出 Top3 分类")
            state.topCategories.forEachIndexed { index, item ->
                TopRow(index = index, name = item.name, amount = item.amount, trailing = "${item.percent}%")
            }

            Spacer(Modifier.height(16.dp))
            SectionHeader("支出 Top3 商家")
            if (state.topMerchants.isEmpty()) {
                Text(
                    "还没有记录商家",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            } else {
                state.topMerchants.forEachIndexed { index, item ->
                    TopRow(index = index, name = item.name, amount = item.amount, trailing = "${item.count}笔")
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun TopRow(
    index: Int,
    name: String,
    amount: Long,
    trailing: String,
) {
    val color = rankColor(index)
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(24.dp).background(color.copy(alpha = 0.18f), CircleShape), contentAlignment = Alignment.Center) {
            Text("${index + 1}", style = MaterialTheme.typography.labelMedium, color = color, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(10.dp))
        Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Text("¥${MoneyFormatter.centsToYuan(amount)}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        Spacer(Modifier.width(8.dp))
        Text(trailing, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LegendDot(
    color: Color,
    text: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Spacer(Modifier.width(4.dp))
        Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
