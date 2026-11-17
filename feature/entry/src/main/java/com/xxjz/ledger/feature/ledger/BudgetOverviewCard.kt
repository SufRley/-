package com.xxjz.ledger.feature.ledger

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xxjz.ledger.core.common.MoneyFormatter
import com.xxjz.ledger.core.designsystem.theme.LocalAmountColors

/**
 * 明细页底部的预算概览卡（v1.8.0 / PRD F4、D7）。
 *
 * 三态：
 * - **没有预算** → 一行引导「设置预算 →」，点击进预算页；
 * - **正常** → 进度条 + 已用 / 剩余；
 * - **超支** → 进度条画满并转红，同时文字提示「已超支 ¥xx」。
 *
 * 卡本体只是一条总预算的进度：分类预算的细账仍在预算页里看，首页不铺开。
 */
@Composable
fun BudgetOverviewCard(
    budget: BudgetCardState,
    onOpen: () -> Unit,
) {
    val colors = LocalAmountColors.current
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 16.dp)
                .clickable(onClick = onOpen)
                .testTag("budget_card"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(14.dp)) {
            if (!budget.hasBudget) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "本月还没设预算",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "设置预算 →",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "本月预算",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "¥${MoneyFormatter.centsToYuan(budget.usedCents)} / ¥${MoneyFormatter.centsToYuan(budget.limitCents ?: 0)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (budget.overspent) colors.overBudget else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("budget_used"),
                    )
                }
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { budget.progress },
                    modifier = Modifier.fillMaxWidth().height(8.dp).testTag("budget_progress"),
                    color = if (budget.overspent) colors.overBudget else MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surface,
                    strokeCap = StrokeCap.Round,
                )
                Spacer(Modifier.height(6.dp))
                val remaining = budget.remainingCents
                Text(
                    text =
                        if (remaining >= 0) {
                            "剩余 ¥${MoneyFormatter.centsToYuan(remaining)}"
                        } else {
                            "已超支 ¥${MoneyFormatter.centsToYuan(-remaining)}"
                        },
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (budget.overspent) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (budget.overspent) colors.overBudget else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("budget_remaining"),
                )
            }
        }
    }
}
