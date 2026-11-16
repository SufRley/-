package com.xxjz.ledger.feature.ledger

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.CalendarViewMonth
import androidx.compose.material.icons.outlined.CallSplit
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Receipt
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 长按流水弹出的动作面板（PRD v1.9.0 第 3 条）。
 *
 * 用户说「长按可编辑」，但长按同时还要出菜单，所以把「编辑」放在菜单第一项——
 * 长按 → 第一项就是编辑，一步到位，和「长按直接进编辑」比只多一次点击。
 *
 * 用底部弹层而不是中间对话框：这些项在手机上竖着排才不挤，且单手更好够到；
 * 「更多操作」的二级才用中间弹窗，因为只有两项，符合用户描述的「中间选项」。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TxnActionSheet(
    summary: String,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onBatch: () -> Unit,
    onReimburse: () -> Unit,
    reimburseLabel: String,
    onSplit: () -> Unit,
    onInstallment: () -> Unit,
    onMore: () -> Unit,
    onDelete: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag("txn_action_sheet")) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            Text(
                text = summary,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 6.dp),
            )
            ActionRow(Icons.Outlined.Edit, "编辑", "打开记账页修改这一笔", "action_edit", onEdit)
            ActionRow(Icons.Outlined.Checklist, "批量操作", "勾选多笔一起处理", "action_batch", onBatch)
            ActionRow(Icons.Outlined.Receipt, reimburseLabel, "标记后可在「待报销」里跟进", "action_reimburse", onReimburse)
            ActionRow(Icons.Outlined.CallSplit, "拆分", "拆成多笔独立账目", "action_split", onSplit)
            ActionRow(Icons.Outlined.CalendarViewMonth, "分期", "查看该账户账单与分期计划", "action_installment", onInstallment)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            ActionRow(Icons.Outlined.MoreHoriz, "更多操作", null, "action_more", onMore)
            // v1.9.4 第 4 条：删除收到抽屉里，放最底且用红色，离常用项最远、误触最少。
            // 不再弹二次确认——与批量删除、原左滑删除口径一致，反悔靠回收站。
            ActionRow(Icons.Outlined.DeleteOutline, "删除", "移入回收站，可恢复", "action_delete", onDelete, danger = true)
            Spacer(Modifier.height(12.dp))
        }
    }
}

/** 「更多操作」的二级中间弹窗：只有创建周期记账 / 创建模板记账两项。 */
@Composable
fun MoreActionsDialog(
    onDismiss: () -> Unit,
    onCreateRecurring: () -> Unit,
    onCreateTemplate: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("更多操作") },
        text = {
            Column {
                ActionRow(Icons.Outlined.Repeat, "创建周期记账", "按固定周期自动记一笔", "more_recurring", onCreateRecurring)
                ActionRow(Icons.Outlined.BookmarkAdd, "创建模板记账", "把这一笔存成模板，下次一键复用", "more_template", onCreateTemplate)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        modifier = Modifier.testTag("more_actions_dialog"),
    )
}

/** 批量模式的操作栏：贴在底部，显示已选数量与可用动作。 */
@Composable
fun BatchActionBar(
    count: Int,
    onCancel: () -> Unit,
    onReimburse: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
        modifier = modifier.fillMaxWidth().testTag("batch_bar"),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("已选 $count 笔", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = onCancel, modifier = Modifier.testTag("batch_cancel")) { Text("取消") }
            TextButton(
                onClick = onReimburse,
                enabled = count > 0,
                modifier = Modifier.testTag("batch_reimburse"),
            ) { Text("标记报销") }
            TextButton(
                onClick = onDelete,
                enabled = count > 0,
                modifier = Modifier.testTag("batch_delete"),
            ) {
                Text("删除", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/** 菜单里的一行：图标 + 标题 +（可选）副标题。 */
@Composable
private fun ActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    tag: String,
    onClick: () -> Unit,
    danger: Boolean = false,
) {
    val tint = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    val titleColor = if (danger) MaterialTheme.colorScheme.error else Color.Unspecified
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp), tint = tint)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = titleColor)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
