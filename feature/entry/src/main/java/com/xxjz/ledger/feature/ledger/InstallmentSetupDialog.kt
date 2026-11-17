package com.xxjz.ledger.feature.ledger

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.xxjz.ledger.core.common.MoneyFormatter
import com.xxjz.ledger.domain.model.FeeMode
import com.xxjz.ledger.domain.model.Installment
import com.xxjz.ledger.domain.model.InstallmentSchedule

/**
 * 对本笔交易设置分期（v1.9.5 第 9 条）。
 *
 * 以前长按「分期」会跳到付款账户的详情页——用户想问的是「**这 3000 元**分 3 期怎么还」，
 * 结果被带到整张卡的账单里，还得自己再找一遍那笔消费。
 *
 * 弹层只问两个数：分几期、已经还了几期。每期金额与本期金额当场算给用户看，
 * 因为「一分钱对不上」是分期最常见的争议点。
 *
 * 用 [Dialog] + [Surface] 而不是 Material3 的 `AlertDialog`：后者给内容区留了最小宽度，
 * 里面再放 `fillMaxWidth` 的输入框会在测量阶段反复拉锯（真机上整页卡住）。
 */
@Composable
fun InstallmentSetupDialog(
    amountCents: Long,
    existing: Installment?,
    onDismiss: () -> Unit,
    onConfirm: (periods: Int, paidPeriods: Int) -> Unit,
) {
    val readOnly = existing != null
    var periodsText by remember { mutableStateOf(existing?.periods?.toString() ?: "") }
    // 已执行期数默认 0：绝大多数人点分期是为了「从现在开始分」
    var paidText by remember { mutableStateOf(existing?.paidPeriods?.toString() ?: "0") }

    val periods = periodsText.toIntOrNull()
    val paid = paidText.toIntOrNull()
    val periodsOk = periods != null && InstallmentSchedule.isValidPeriods(periods)
    val paidOk = paid != null && paid >= 0 && (periods == null || paid < periods)

    val schedule =
        if (periodsOk && amountCents > 0L) {
            InstallmentSchedule.of(amountCents, 0L, FeeMode.AVERAGE, periods!!)
        } else {
            emptyList()
        }
    val current = schedule.getOrNull(paid ?: 0)
    val perPeriod = schedule.firstOrNull()

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            // widthIn 给一个确定的最大宽度：Dialog 窗口的宽度约束是「至多屏幕宽、可以无限」，
            // 文本在无限宽下测量会让 Robolectric 的 LineBreaker 一直注册新对象（真机上则是量不出结果）
            modifier = Modifier.widthIn(max = 320.dp).fillMaxWidth().testTag("installment_dialog"),
        ) {
            Column(Modifier.padding(20.dp)) {
                Text(
                    text = if (readOnly) "分期详情" else "分期",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(12.dp))
                InfoRow("本金", "¥${MoneyFormatter.centsToYuan(amountCents)}")
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = periodsText,
                    onValueChange = { periodsText = it.filter(Char::isDigit).take(2) },
                    label = { Text("期数（2~60）") },
                    singleLine = true,
                    enabled = !readOnly,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().testTag("inst_periods"),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = paidText,
                    onValueChange = { paidText = it.filter(Char::isDigit).take(2) },
                    label = { Text("已执行期数") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().testTag("inst_paid"),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "已经在线下还过几期就填几，之后从下一期开始自动出账",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (perPeriod != null && current != null) {
                    Spacer(Modifier.height(12.dp))
                    InfoRow("每期", "¥${MoneyFormatter.centsToYuan(perPeriod.totalCents)}")
                    Spacer(Modifier.height(4.dp))
                    InfoRow(
                        label = "本期（第 ${(paid ?: 0) + 1} 期）",
                        value = "¥${MoneyFormatter.centsToYuan(current.totalCents)}",
                        tag = "inst_current",
                    )
                }
                if (!paidOk) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "已执行期数要在 0 到 ${(periods ?: 1) - 1} 之间",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss, modifier = Modifier.testTag("inst_cancel")) {
                        Text(if (readOnly) "关闭" else "取消")
                    }
                    TextButton(
                        onClick = { if (periods != null && paid != null) onConfirm(periods, paid) },
                        enabled = !readOnly && periodsOk && paidOk,
                        modifier = Modifier.testTag("inst_confirm"),
                    ) { Text("确认分期") }
                }
            }
        }
    }
}

@Composable
private fun InfoRow(
    label: String,
    value: String,
    tag: String? = null,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            modifier = if (tag != null) Modifier.testTag(tag) else Modifier,
        )
    }
}
