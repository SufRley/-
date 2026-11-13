package com.xxjz.ledger.feature.report

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xxjz.ledger.core.common.DateUtils
import com.xxjz.ledger.core.common.MoneyFormatter
import com.xxjz.ledger.core.designsystem.component.EmptyState
import com.xxjz.ledger.core.designsystem.theme.LocalAmountColors

/**
 * P08 分类下钻（AC-38 / AC-39）。
 *
 * 面包屑 → 合计 → 环比 → 二级分类分解 → 该分类流水。
 */
@Composable
fun CategoryDrillScreen(
    categoryId: String,
    onBack: () -> Unit,
    onOpenTxn: (String) -> Unit = {},
    viewModel: CategoryDrillViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(categoryId) { viewModel.load(categoryId) }
    val colors = LocalAmountColors.current

    Column(Modifier.fillMaxSize().testTag("screen_drill")) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, modifier = Modifier.testTag("drill_back")) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = state.breadcrumb.joinToString(" › ").ifBlank { state.title },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("drill_breadcrumb"),
                )
                Text(
                    text = state.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                text = state.monthLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 16.dp),
            )
        }

        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        ) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("本月合计", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        text = "¥${MoneyFormatter.centsToYuan(state.total)}",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = colors.expense,
                        modifier = Modifier.testTag("drill_total"),
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("较上月同期", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ChangeLabel(percent = state.changePercent)
                    Text(
                        text = "上月同期 ¥${MoneyFormatter.centsToYuan(state.prevTotal)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))

        if (state.total == 0L && !state.loading) {
            EmptyState(
                title = "本月这个分类还没有支出",
                description = "换个月份或记一笔试试",
                modifier = Modifier.fillMaxSize(),
            )
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            if (state.children.isNotEmpty()) {
                item(key = "children_head") {
                    SectionTitle("二级分类")
                }
                items(state.children, key = { "c_${it.id}" }) { child ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(child.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        Column(horizontalAlignment = Alignment.End) {
                            Text("¥${MoneyFormatter.centsToYuan(child.amount)}", style = MaterialTheme.typography.bodyMedium)
                            ChangeLabel(percent = child.changePercent)
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "${child.percent}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                item(key = "divider") { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            }
            item(key = "rows_head") { SectionTitle("流水明细（${state.rows.size} 笔）") }
            items(state.rows, key = { it.txn.id }) { row ->
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable { onOpenTxn(row.txn.id) }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = row.txn.note.ifBlank { row.categoryName },
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "${DateUtils.friendlyDate(DateUtils.toDate(row.txn.dateTime))} · ${row.categoryName}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = "¥${MoneyFormatter.centsToYuan(row.txn.amount)}",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = colors.expense,
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
