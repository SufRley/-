package com.xxjz.ledger.feature.report

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xxjz.ledger.core.common.MoneyFormatter
import com.xxjz.ledger.core.designsystem.theme.LocalAmountColors

/** 排行配色盘：按顺序取，保证相邻色可辨。 */
val RankPalette: List<Color> =
    listOf(
        Color(0xFF4E8F76),
        Color(0xFFD97757),
        Color(0xFF6C8AC7),
        Color(0xFFC7A34E),
        Color(0xFF9B7BB8),
        Color(0xFF5EA3A3),
        Color(0xFFB86B8E),
        Color(0xFF8A9A5B),
    )

fun rankColor(index: Int): Color = RankPalette[index % RankPalette.size]

/** 排名徽标：前三名用配色，其余灰字。 */
@Composable
fun RankBadge(
    index: Int,
    color: Color,
) {
    Box(
        modifier =
            Modifier
                .size(22.dp)
                .background(if (index < 3) color.copy(alpha = 0.18f) else Color.Transparent, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "${index + 1}",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (index < 3) FontWeight.Bold else FontWeight.Normal,
            color = if (index < 3) color else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 分类排行（P07 AC-37）：图标色点、名称、笔数、占比条、金额，点击下钻。
 *
 * 饼图与排行共用 [RankItem.percent]，两边数字始终一致。
 */
@Composable
fun RankList(
    items: List<RankItem>,
    onSelect: (RankItem) -> Unit,
    modifier: Modifier = Modifier,
    selectedId: String? = null,
) {
    LazyColumn(modifier = modifier.testTag("rank_list")) {
        items(items, key = { it.id }) { item ->
            val color = rankColor(items.indexOf(item))
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(item) }
                        .background(
                            if (selectedId == item.id) color.copy(alpha = 0.08f) else Color.Transparent,
                        ).padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RankBadge(index = items.indexOf(item), color = color)
                Spacer(Modifier.width(10.dp))
                Box(Modifier.size(10.dp).background(color, CircleShape))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = item.name,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (item.count > 0) {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "${item.count}笔",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    PercentBar(percent = item.percent, color = color)
                }
                Spacer(Modifier.width(12.dp))
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "¥${MoneyFormatter.centsToYuan(item.amount)}",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = "${item.percent}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** 占比条：宽度按百分比动画展开。 */
@Composable
fun PercentBar(
    percent: Int,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val target = (percent.coerceIn(0, 100) / 100f)
    val animated by animateFloatAsState(targetValue = target, label = "percent")
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(4.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(2.dp)),
    ) {
        if (animated > 0f) {
            LinearProgressIndicator(
                progress = { animated },
                color = color,
                trackColor = Color.Transparent,
                modifier = Modifier.fillMaxWidth().height(4.dp),
            )
        }
    }
}

/** 环比标签：涨为支出色（多花了），应为收入色（少花了），随配色反转（AC-39/AC-41）。 */
@Composable
fun ChangeLabel(
    percent: Int?,
    modifier: Modifier = Modifier,
    /** 支出增长是坏消息，所以涨用支出色；收入反过来。 */
    expenseMode: Boolean = true,
) {
    if (percent == null) {
        Text(
            text = "无同期数据",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier,
        )
        return
    }
    val colors = LocalAmountColors.current
    val up = percent > 0
    val color =
        when {
            percent == 0 -> MaterialTheme.colorScheme.onSurfaceVariant
            up == expenseMode -> colors.expense
            else -> colors.income
        }
    val arrow =
        when {
            percent > 0 -> "↑"
            percent < 0 -> "↓"
            else -> "—"
        }
    Text(
        text = "$arrow ${kotlin.math.abs(percent)}%",
        style = MaterialTheme.typography.labelMedium,
        color = color,
        modifier = modifier.testTag("change_label"),
    )
}

/** 顶部切换胶囊（支出/收入、周/月/年、饼图/排行/趋势）。 */
@Composable
fun CapsuleRow(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        labels.forEachIndexed { index, label ->
            val active = index == selectedIndex
            Surface(
                onClick = { onSelect(index) },
                shape = RoundedCornerShape(50),
                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                contentColor =
                    if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f).testTag("capsule_$label"),
            ) {
                Box(Modifier.padding(vertical = 7.dp), contentAlignment = Alignment.Center) {
                    Text(text = label, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}
