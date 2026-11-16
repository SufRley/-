package com.xxjz.ledger.feature.ledger

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.xxjz.ledger.core.common.MoneyFormatter
import com.xxjz.ledger.domain.model.Installment

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
    var showInstallment by remember(target) { mutableStateOf(false) }
    var existingInstallment by remember(target) { mutableStateOf<Installment?>(null) }
    var installmentAllowed by remember(target) { mutableStateOf(true) }
    // 查两件事：能不能分期（只有信用支出）、有没有分过（有则切只读，避免重复建计划）
    LaunchedEffect(target) {
        installmentAllowed = viewModel.canInstallment(txn)
        existingInstallment = viewModel.installmentOf(txn.id)
    }
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
        labels =
            SheetLabels(
                reimburse = if (txn.pendingReimbursement) "取消待报销" else "报销",
                installment = if (existingInstallment == null) "分期" else "查看分期",
                showInstallment = installmentAllowed,
            ),
        onSplit = {
            onOpenSplit(txn.id)
            onDismiss()
        },
        // 分期就地弹层，作用在本笔交易上（v1.9.5 第 9 条）。
        // 以前这里直接把用户带到付款账户的详情页，等于问「这笔怎么还」却答了「这张卡欠多少」。
        onInstallment = { showInstallment = true },
        onMore = { showMore = true },
        onDelete = {
            onDelete(target)
            onDismiss()
        },
    )

    if (showInstallment) {
        InstallmentSetupDialog(
            amountCents = txn.amount,
            existing = existingInstallment,
            onDismiss = {
                showInstallment = false
                onDismiss()
            },
            onConfirm = { periods, paid ->
                viewModel.startInstallment(txn, periods, paid)
                showInstallment = false
                onDismiss()
            },
        )
    }

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
