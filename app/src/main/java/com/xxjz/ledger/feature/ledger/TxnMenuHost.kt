package com.xxjz.ledger.feature.ledger

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.xxjz.ledger.core.common.MoneyFormatter

/**
 * 长按菜单的宿主（PRD v1.9.0 第 3 条）。
 *
 * 一级用底部动作面板、二级「更多操作」用中间弹窗、拆分再叠一个输入框。
 * 三级弹窗的状态收在这一个 Composable 里，[LedgerScreen] 只需要记住「当前对哪一条操作」。
 *
 * 二级弹窗取消时只收起自己、保留底部面板：用户点错「更多操作」还能接着选别的，
 * 不用从头再长按一次。
 */
@Composable
fun TxnMenuHost(
    target: TxnRow?,
    viewModel: LedgerViewModel,
    onOpenAccount: (String) -> Unit,
    onOpenRecurring: () -> Unit,
    onEditTxn: (String) -> Unit,
    onStartBatch: (TxnRow) -> Unit,
    onDelete: (TxnRow) -> Unit,
    onOpenSplit: (String) -> Unit = {},
    onDismiss: () -> Unit,
) {
    if (target == null) return
    val txn = target.txn
    var showMore by remember(target) { mutableStateOf(false) }
    val amountText = MoneyFormatter.centsToYuan(txn.amount)

    // 一级面板始终留在下面：二级弹窗关掉后还能接着选别的
    TxnActionSheet(
        summary = "${target.categoryName} · ¥$amountText",
        onDismiss = onDismiss,
        onEdit = {
            onEditTxn(txn.id)
            onDismiss()
        },
        onBatch = {
            onStartBatch(target)
            onDismiss()
        },
        onReimburse = {
            viewModel.toggleReimbursement(txn)
            onDismiss()
        },
        reimburseLabel = if (txn.pendingReimbursement) "取消待报销" else "报销",
        onSplit = {
            onOpenSplit(txn.id)
            onDismiss()
        },
        onInstallment = {
            // accountId 理论上有值；万一为空就别跳，免得进到一个空账户页
            txn.accountId?.let(onOpenAccount)
            onDismiss()
        },
        onMore = { showMore = true },
        onDelete = {
            onDelete(target)
            onDismiss()
        },
    )

    if (showMore) {
        MoreActionsDialog(
            onDismiss = { showMore = false },
            onCreateRecurring = {
                showMore = false
                onDismiss()
                onOpenRecurring()
            },
            onCreateTemplate = {
                viewModel.saveAsTemplate(txn, target.categoryName)
                showMore = false
                onDismiss()
            },
        )
    }
}
