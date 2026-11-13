package com.xxjz.ledger.feature.budget

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xxjz.ledger.core.common.MoneyFormatter

/** 预算进度条：未超支用主色，超支用错误色。 */
@Composable
fun BudgetBar(
    progress: BudgetProgress,
    modifier: Modifier = Modifier,
    height: Int = 6,
) {
    val color = statusColor(progress.status, progress.overspent)
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(height.dp)
                .clip(RoundedCornerShape(percent = 50))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .testTag("budget_bar"),
    ) {
        if (progress.barFraction > 0f) {
            Box(
                Modifier
                    .fillMaxWidth(progress.barFraction)
                    .height(height.dp)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(color),
            )
        }
    }
}

/** 状态对应的颜色。 */
@Composable
fun statusColor(
    status: BudgetStatus,
    overspent: Boolean,
): Color =
    when {
        overspent || status == BudgetStatus.OVER -> MaterialTheme.colorScheme.error
        status == BudgetStatus.WARNING -> MaterialTheme.colorScheme.tertiary
        status == BudgetStatus.DISABLED -> MaterialTheme.colorScheme.outline
        else -> MaterialTheme.colorScheme.primary
    }

/** 剩余 / 超支一句话（M6-T2 文案）。 */
fun remainingText(progress: BudgetProgress): String =
    when {
        progress.status == BudgetStatus.OVER -> "已超支 ¥${MoneyFormatter.centsToYuan(progress.over)}"
        progress.remaining <= 0 -> "已用完"
        else -> "还剩 ¥${MoneyFormatter.centsToYuan(progress.remaining)}"
    }

/** 日均可花文案，没有可花额度时给出「已用完」提示。 */
fun dailyText(progress: BudgetProgress): String? =
    when {
        progress.status == BudgetStatus.DISABLED -> "已停用"
        progress.dailyAvailable != null -> "日均可花 ¥${MoneyFormatter.centsToYuan(progress.dailyAvailable!!)}"
        progress.status == BudgetStatus.OVER || progress.remaining <= 0 -> "预算已用完"
        else -> null
    }

/** 单条预算行：名称 + 已用/额度 + 进度条 + 状态。 */
@Composable
fun BudgetRow(
    progress: BudgetProgress,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    Surface(
        modifier = modifier.fillMaxWidth().testTag("budget_row"),
        color = MaterialTheme.colorScheme.surface,
        onClick = { onClick?.invoke() },
        enabled = onClick != null,
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = progress.budget.name,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                        )
                        if (progress.inherited) {
                            Spacer(Modifier.size(6.dp))
                            Text(
                                "沿用",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                    if (progress.categoryLabel.isNotBlank()) {
                        Text(
                            text = progress.categoryLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    text = "¥${MoneyFormatter.centsToYuan(progress.used)} / ${MoneyFormatter.centsToYuan(progress.budget.amount)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color =
                        if (progress.overspent) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                )
            }
            Spacer(Modifier.height(8.dp))
            BudgetBar(progress)
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = remainingText(progress),
                    style = MaterialTheme.typography.labelMedium,
                    color =
                        if (progress.overspent) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                )
                val hint = dailyText(progress)
                if (hint != null) {
                    Text(
                        text = hint,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
