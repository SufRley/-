package com.xxjz.ledger.feature.account

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xxjz.ledger.core.common.DateUtils
import com.xxjz.ledger.core.common.MoneyFormatter
import com.xxjz.ledger.core.designsystem.component.AmountDirection
import com.xxjz.ledger.core.designsystem.component.AmountText
import com.xxjz.ledger.core.designsystem.component.ConfirmDialog
import com.xxjz.ledger.core.designsystem.component.EmptyState
import com.xxjz.ledger.core.designsystem.theme.profitColor
import com.xxjz.ledger.domain.model.Account
import com.xxjz.ledger.domain.model.AccountType
import com.xxjz.ledger.domain.model.DebtAction
import com.xxjz.ledger.domain.model.FeeMode
import com.xxjz.ledger.domain.model.InstallmentSchedule
import com.xxjz.ledger.domain.model.Txn
import com.xxjz.ledger.domain.model.TxnType
import com.xxjz.ledger.domain.usecase.CreditBill

/** P14 账户详情：余额、本月收支、笔数、该账户流水；信用类显示额度进度（AC-18 / AC-24）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountDetailScreen(
    onBack: () -> Unit,
    onOpenTxn: (String) -> Unit,
    viewModel: AccountDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var editingMarket by remember { mutableStateOf(false) }
    // 借贷操作弹窗：null 表示关闭，否则是当前要执行的动作
    var debtOp by remember { mutableStateOf<DebtAction?>(null) }
    var showWriteOff by remember { mutableStateOf(false) }
    // 分期 / 还款弹窗（v1.9.0 信用账户）
    var showInstallment by remember { mutableStateOf(false) }
    var showRepayBill by remember { mutableStateOf(false) }
    // 合并到其他借贷账户（v1.9.0 / PRD 3.4）
    var showMerge by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.message.collect { snackbar.showSnackbar(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.account?.name ?: "账户详情") },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("account_detail_back")) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    // 借贷账户才给合并入口：历史数据里同一个人可能被建成了两个账户
                    if (state.account?.type == AccountType.DEBT && state.mergeTargets.isNotEmpty()) {
                        TextButton(
                            onClick = { showMerge = true },
                            modifier = Modifier.testTag("account_merge"),
                        ) { Text("合并到…") }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            AccountSummaryCard(
                state = state,
                onEditMarketValue = { editingMarket = true },
                onToggleInclude = viewModel::setIncludeInNetAssets,
                onDebtOp = { debtOp = it },
                onWriteOff = { showWriteOff = true },
                onSplit = { showInstallment = true },
                onRepayBill = { showRepayBill = true },
            )

            if (state.rows.isEmpty() && !state.loading) {
                EmptyState(
                    title = "该账户还没有流水",
                    description = "记账时选择这个账户即可",
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(state.rows, key = { it.txn.id }) { row ->
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(row.categoryName, style = MaterialTheme.typography.bodyLarge)
                                    row.installmentPeriods?.let { n ->
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            text = "已分 $n 期",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.testTag("inst_badge_${row.txn.id}"),
                                        )
                                    }
                                }
                                Text(
                                    DateUtils.friendlyDate(DateUtils.toDate(row.txn.dateTime)) +
                                        " · " + row.txn.note.ifBlank { debtActionText(row.txn.debtAction) ?: typeText(row.txn.type) },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            AmountText(
                                cents = row.txn.amount,
                                direction =
                                    when (row.txn.type) {
                                        TxnType.INCOME -> AmountDirection.INCOME
                                        TxnType.EXPENSE -> AmountDirection.EXPENSE
                                        else -> AmountDirection.NEUTRAL
                                    },
                                signed = row.txn.type != TxnType.TRANSFER,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                }
            }
        }
    }

    debtOp?.let { action ->
        DebtOpDialog(
            action = action,
            remainingCents = remainingFor(action, state.account?.balance ?: 0),
            fundAccounts = state.fundAccounts,
            onDismiss = { debtOp = null },
            onConfirm = { cents, fundId ->
                viewModel.recordDebt(action, cents, fundId)
                debtOp = null
            },
        )
    }

    if (showWriteOff) {
        ConfirmDialog(
            title = "坏账核销",
            message =
                "确认这笔 ¥${MoneyFormatter.centsToYuan(kotlin.math.abs(state.account?.balance ?: 0))} 收不回来了？" +
                    "核销后待收清零，净资产会相应减少，记录保留在流水里。",
            confirmText = "核销",
            dismissText = "取消",
            destructive = true,
            onConfirm = {
                viewModel.writeOff()
                showWriteOff = false
            },
            onDismiss = { showWriteOff = false },
        )
    }

    state.bill?.takeIf { showInstallment }?.let { _ ->
        InstallmentDialog(
            candidates = state.installmentCandidates,
            categoryNameOf = { txn -> state.rows.firstOrNull { it.txn.id == txn.id }?.categoryName ?: "消费" },
            onDismiss = { showInstallment = false },
            onConfirm = { txnId, principal, periods, fee, mode ->
                viewModel.createInstallment(txnId, principal, periods, fee, mode)
                showInstallment = false
            },
        )
    }

    state.bill?.takeIf { showRepayBill }?.let { bill ->
        DebtOpDialog(
            action = DebtAction.REPAY,
            // 只用于提示本期应还，不设上限
            remainingCents = bill.dueCents,
            fundAccounts = state.fundAccounts,
            onDismiss = { showRepayBill = false },
            onConfirm = { cents, fundId ->
                viewModel.repayBill(cents, fundId)
                showRepayBill = false
            },
        )
    }

    if (showMerge) {
        MergeTargetDialog(
            current = state.account,
            targets = state.mergeTargets,
            onDismiss = { showMerge = false },
            onConfirm = { targetId ->
                viewModel.mergeInto(targetId)
                showMerge = false
            },
        )
    }

    if (editingMarket) {
        MarketValueDialog(
            current = state.account?.marketValueCents ?: 0,
            onDismiss = { editingMarket = false },
            onConfirm = { cents ->
                viewModel.updateMarketValue(cents)
                editingMarket = false
            },
        )
    }
}

/** 账户详情顶部摘要卡（v1.9.0 从 [AccountDetailScreen] 抽出，避免单个 Composable 过长）。 */
@Composable
private fun AccountSummaryCard(
    state: AccountDetailUiState,
    onEditMarketValue: () -> Unit,
    onToggleInclude: (Boolean) -> Unit,
    onDebtOp: (DebtAction) -> Unit,
    onWriteOff: () -> Unit,
    onSplit: () -> Unit,
    onRepayBill: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().padding(16.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text =
                    when {
                        state.account?.type == AccountType.CREDIT -> "当前欠款"
                        // v1.9.0：借贷账户余额的符号本身就是方向（待收为正、待还为负）
                        state.account?.type == AccountType.DEBT && (state.account?.balance ?: 0) < 0 -> "待还"
                        state.account?.type == AccountType.DEBT -> "待收"
                        else -> "当前余额"
                    },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "¥${MoneyFormatter.centsToYuan(kotlin.math.abs(state.account?.balance ?: 0))}",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color =
                    if (state.account?.type == AccountType.DEBT) {
                        // 待收绿、待还红：与「红涨绿跌」相反，这里是「钱在手上」与「钱欠着」
                        if ((state.account?.balance ?: 0) >= 0) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        }
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                modifier = Modifier.testTag("account_balance_text"),
            )
            Spacer(Modifier.height(10.dp))
            Row {
                StatCell("本月收入", state.monthIncome, AmountDirection.INCOME, Modifier.weight(1f))
                StatCell("本月支出", state.monthExpense, AmountDirection.EXPENSE, Modifier.weight(1f))
                Column(Modifier.weight(1f)) {
                    Text("笔数", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${state.totalCount}", style = MaterialTheme.typography.bodyMedium)
                }
            }
            state.account
                ?.takeIf { it.type == AccountType.INVEST }
                ?.let { invest ->
                    Spacer(Modifier.height(12.dp))
                    // 投资账户：成本 / 市值 / 盈亏三行 + 两个操作（PRD F2、D2）
                    InvestSummary(
                        account = invest,
                        onEditMarketValue = onEditMarketValue,
                        onToggleInclude = onToggleInclude,
                    )
                }
            state.account
                ?.takeIf { it.type == AccountType.DEBT }
                ?.let { debt ->
                    Spacer(Modifier.height(12.dp))
                    DebtSummary(
                        account = debt,
                        onLendOut = { onDebtOp(DebtAction.LEND_OUT) },
                        onCollect = { onDebtOp(DebtAction.COLLECT) },
                        onBorrow = { onDebtOp(DebtAction.BORROW_IN) },
                        onRepay = { onDebtOp(DebtAction.REPAY) },
                        onWriteOff = onWriteOff,
                    )
                }
            // 信用账户账单卡（v1.9.0 / PRD 3.5）：
            // 本期应还、本期消费、还款日 + 分期 / 还款两个按钮
            state.bill?.let { bill ->
                state.account?.let { credit ->
                    Spacer(Modifier.height(12.dp))
                    CreditBillCard(
                        bill = bill,
                        account = credit,
                        onSplit = onSplit,
                        onRepay = onRepayBill,
                    )
                }
            }
        }
    }
}

/** 投资账户摘要：成本、市值、盈亏额与盈亏率，红涨绿跌。 */
@Composable
private fun InvestSummary(
    account: Account,
    onEditMarketValue: () -> Unit,
    onToggleInclude: (Boolean) -> Unit,
) {
    Column(Modifier.testTag("invest_summary")) {
        Row {
            InvestCell("累计成本", account.costCents, Modifier.weight(1f))
            InvestCell("当前市值", account.marketValueCents, Modifier.weight(1f))
            Column(Modifier.weight(1f)) {
                Text("盈亏", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    text =
                        "¥${MoneyFormatter.centsToYuan(account.profitCents)}" +
                            if (account.costCents > 0) "（${profitPercentText(account.profitRate)}）" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = profitColor(account.profitCents),
                    modifier = Modifier.testTag("invest_profit"),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("计入净资产", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Switch(
                checked = account.includeInNetAssets,
                onCheckedChange = onToggleInclude,
                modifier = Modifier.testTag("invest_include_switch"),
            )
        }
        TextButton(onClick = onEditMarketValue, modifier = Modifier.testTag("edit_market_value")) {
            Text("修改市值")
        }
    }
}

@Composable
private fun InvestCell(
    title: String,
    cents: Long,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text(title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("¥${MoneyFormatter.centsToYuan(cents)}", style = MaterialTheme.typography.bodyMedium)
    }
}

/** 盈亏率文案：+12.5% / -3.2%，保留一位小数即可，细到 0.01% 用户也用不上。 */
internal fun profitPercentText(rate: Double): String {
    val pct = rate * 100
    val sign = if (pct > 0) "+" else ""
    return "$sign${String.format(java.util.Locale.CHINA, "%.1f", pct)}%"
}

@Composable
private fun MarketValueDialog(
    current: Long,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit,
) {
    var text by remember { mutableStateOf(MoneyFormatter.centsToPlain(current)) }
    val cents = MoneyFormatter.yuanToCents(text) ?: 0L
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("修改市值") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.filter { c -> c.isDigit() || c == '.' }.take(14) },
                    label = { Text("当前市值") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth().testTag("market_value_input"),
                )
                Text(
                    "改市值不会生成记账流水，只影响盈亏与净资产",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(cents) }, modifier = Modifier.testTag("market_value_save")) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun StatCell(
    title: String,
    amount: Long,
    direction: AmountDirection,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text(title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        AmountText(cents = amount, direction = direction, signed = false, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun typeText(type: TxnType): String =
    when (type) {
        TxnType.EXPENSE -> "支出"
        TxnType.INCOME -> "收入"
        TxnType.TRANSFER -> "转账"
        TxnType.DEBT -> "借贷"
    }

/**
 * 借贷账户摘要（v1.9.0 / PRD 3 / 净值模型）。
 *
 * 一人一账户，余额符号即方向：正数待收、负数待还、零已结清。
 * 四个动作常驻（借出 / 收款 / 借入 / 还款），随时可在同一账户上记任意一笔；
 * 坏账核销只对「待收」开放——待还时核销等于凭空虚增净资产。
 */
@Composable
private fun DebtSummary(
    account: Account,
    onLendOut: () -> Unit,
    onCollect: () -> Unit,
    onBorrow: () -> Unit,
    onRepay: () -> Unit,
    onWriteOff: () -> Unit,
) {
    val balance = account.balance
    Column(Modifier.testTag("debt_summary")) {
        Text(
            text =
                when {
                    balance > 0 -> "待收 · 对方欠我"
                    balance < 0 -> "待还 · 我欠对方"
                    else -> "已结清"
                },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("debt_state_text"),
        )
        account.dueAt?.let {
            Text(
                text = "约定还款日：${DateUtils.friendlyDate(DateUtils.toDate(it))}",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        if (account.note.isNotBlank()) {
            Text(
                text = account.note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onLendOut, modifier = Modifier.testTag("debt_lend_out")) { Text("借出") }
            OutlinedButton(onClick = onBorrow, modifier = Modifier.testTag("debt_borrow")) { Text("借入") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            Button(onClick = onCollect, modifier = Modifier.testTag("debt_collect")) { Text("收款") }
            Button(onClick = onRepay, modifier = Modifier.testTag("debt_repay")) { Text("还款") }
            if (balance > 0) {
                TextButton(onClick = onWriteOff, modifier = Modifier.testTag("debt_write_off")) { Text("坏账核销") }
            }
        }
    }
}

/**
 * 借贷动作弹窗：金额 + 资金账户。
 *
 * v1.9.0 净值模型下不再限制金额上限——余额符号由流水自然翻转，
 * 「收多了」就变成我欠对方，无需拦截。
 */
@Composable
private fun DebtOpDialog(
    action: DebtAction,
    remainingCents: Long,
    fundAccounts: List<Account>,
    onDismiss: () -> Unit,
    onConfirm: (Long, String) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    var fundId by remember { mutableStateOf(fundAccounts.firstOrNull()?.id) }
    val cents = MoneyFormatter.yuanToCents(text) ?: 0L
    val label = debtActionText(action) ?: "记账"
    val incoming = action == DebtAction.COLLECT || action == DebtAction.BORROW_IN
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(label) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.filter { c -> c.isDigit() || c == '.' }.take(14) },
                    label = { Text("${label}金额") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth().testTag("debt_amount_input"),
                )
                Text(
                    text = remainingHint(action, remainingCents),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Spacer(Modifier.height(10.dp))
                Text(if (incoming) "进入哪个账户" else "从哪个账户出", style = MaterialTheme.typography.bodySmall)
                fundAccounts.forEach { account ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { fundId = account.id }
                            .padding(vertical = 8.dp)
                            .testTag("debt_fund_${account.id}"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = fundId == account.id, onClick = { fundId = account.id })
                        Text(account.name, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { fundId?.let { onConfirm(cents, it) } },
                enabled = cents > 0L && fundId != null,
                modifier = Modifier.testTag("debt_op_confirm"),
            ) { Text("确认") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 弹窗副标题：只做提示，不做拦截。 */
private fun remainingHint(
    action: DebtAction,
    remaining: Long,
): String =
    when {
        remaining <= 0L -> "该账户当前没有${if (action == DebtAction.COLLECT) "待收" else "待还"}，记完会自动反向"
        action == DebtAction.COLLECT -> "当前待收 ¥${MoneyFormatter.centsToYuan(remaining)}"
        action == DebtAction.REPAY -> "当前待还 ¥${MoneyFormatter.centsToYuan(remaining)}"
        else -> "金额不限，净值自动结算"
    }

/** 余额正为待收、负为待还，取对应侧供提示用。 */
private fun remainingFor(
    action: DebtAction,
    balance: Long,
): Long =
    when (action) {
        DebtAction.COLLECT -> balance.coerceAtLeast(0L)
        DebtAction.REPAY -> (-balance).coerceAtLeast(0L)
        else -> 0L
    }

/** 借贷流水的副标题：优先显示动作，让「一笔借出」和「一笔收款」在列表里能分清。 */
private fun debtActionText(action: DebtAction?): String? =
    when (action) {
        DebtAction.LEND_OUT -> "借出"
        DebtAction.COLLECT -> "收款"
        DebtAction.BORROW_IN -> "借入"
        DebtAction.REPAY -> "还款"
        DebtAction.WRITE_OFF -> "坏账核销"
        null -> null
    }

/**
 * 信用账户账单卡（v1.9.0 / PRD 3.5）。
 *
 * 三个数字一起显示，避免歧义：「本期应还」回答该还多少，「本期消费」回答花了多少，
 * 「还款日」回答什么时候还。已分期的消费由分期计划接管，不再按原额进应还。
 */
@Composable
private fun CreditBillCard(
    bill: CreditBill,
    account: Account,
    onSplit: () -> Unit,
    onRepay: () -> Unit,
) {
    Column(Modifier.testTag("credit_bill_card")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("本期应还", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    text = "¥${MoneyFormatter.centsToYuan(bill.dueCents)}",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.testTag("credit_bill_due"),
                )
            }
            account.cardLimit?.let {
                Column(horizontalAlignment = Alignment.End) {
                    Text("额度", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("¥${MoneyFormatter.centsToYuan(it)}", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = "本期消费 ¥${MoneyFormatter.centsToYuan(bill.periodSpendCents)} · ${bill.repayAtText} 还款",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("credit_bill_spend"),
        )
        val used = account.balance
        val limit = account.cardLimit
        if (used < 0 && limit != null) {
            Text(
                text = "已用 ¥${MoneyFormatter.centsToYuan(-used)} / 可用 ¥${MoneyFormatter.centsToYuan(limit + used)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (bill.activePlans.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            bill.activePlans.forEach { progress ->
                Text(
                    text =
                        "分期 ¥${MoneyFormatter.centsToYuan(progress.plan.principalCents)} · " +
                            "${progress.perPeriodText} · 已还 ${progress.paidPeriods}/${progress.periods} 期",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 1.dp),
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onSplit, modifier = Modifier.testTag("credit_split")) { Text("分期") }
            OutlinedButton(onClick = onRepay, modifier = Modifier.testTag("credit_repay")) { Text("还款") }
        }
    }
}

/**
 * 出单弹窗的步骤：先选分期对象，再填期数手续费。
 */
@Composable
private fun InstallmentDialog(
    candidates: List<Txn>,
    categoryNameOf: (Txn) -> String,
    onDismiss: () -> Unit,
    onConfirm: (txnId: String?, principalCents: Long, periods: Int, feeCents: Long, feeMode: FeeMode) -> Unit,
) {
    // null = 账单分期；非空 = 该笔消费分期
    var pickedTxnId by remember { mutableStateOf<String?>(null) }
    var wholeBill by remember { mutableStateOf(true) }
    var periodsText by remember { mutableStateOf("") }
    var feeText by remember { mutableStateOf("") }
    var feeMode by remember { mutableStateOf(FeeMode.AVERAGE) }

    val periods = periodsText.toIntOrNull()
    val feeCents = MoneyFormatter.yuanToCents(feeText) ?: 0L
    val principal =
        if (wholeBill) {
            candidates.sumOf { it.amount }
        } else {
            candidates.firstOrNull { it.id == pickedTxnId }?.amount ?: 0L
        }
    val periodsOk = periods != null && InstallmentSchedule.isValidPeriods(periods)
    val principalOk = principal > 0L
    val preview =
        if (periodsOk && principalOk) {
            InstallmentSchedule.of(principal, feeCents, feeMode, periods!!)
        } else {
            emptyList()
        }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("分期") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = wholeBill, onClick = { wholeBill = true })
                    Text("本期账单", style = MaterialTheme.typography.bodyMedium)
                }
                Text(
                    "本期共 ${candidates.size} 笔消费",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (candidates.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = !wholeBill, onClick = { wholeBill = false })
                        Text("选一笔消费", style = MaterialTheme.typography.bodyMedium)
                    }
                    if (!wholeBill) {
                        candidates.forEach { txn ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { pickedTxnId = txn.id }
                                    .padding(vertical = 6.dp)
                                    .testTag("inst_pick_${txn.id}"),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = pickedTxnId == txn.id, onClick = { pickedTxnId = txn.id })
                                Text(categoryNameOf(txn), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                Text("¥${MoneyFormatter.centsToYuan(txn.amount)}", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("本金 ¥${MoneyFormatter.centsToYuan(principal)}", style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = periodsText,
                    onValueChange = { periodsText = it.filter { c -> c.isDigit() }.take(2) },
                    label = { Text("期数（2~60）") },
                    singleLine = true,
                    isError = periodsText.isNotEmpty() && !periodsOk,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().testTag("inst_periods_input"),
                )
                if (periodsText.isNotEmpty() && !periodsOk) {
                    Text(
                        text = "请输入 2~60 之间的期数",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("inst_periods_error"),
                    )
                }
                OutlinedTextField(
                    value = feeText,
                    onValueChange = { feeText = it.filter { c -> c.isDigit() || c == '.' }.take(12) },
                    label = { Text("手续费（可填 0）") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("inst_fee_input"),
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    RadioButton(selected = feeMode == FeeMode.AVERAGE, onClick = { feeMode = FeeMode.AVERAGE })
                    Text("手续费均摊", style = MaterialTheme.typography.bodySmall)
                    RadioButton(selected = feeMode == FeeMode.FIRST, onClick = { feeMode = FeeMode.FIRST })
                    Text("首期收齐", style = MaterialTheme.typography.bodySmall)
                }
                if (preview.isNotEmpty()) {
                    Text(
                        text =
                            "每期约 ¥${MoneyFormatter.centsToYuan(preview.first().totalCents)}" +
                                (
                                    if (preview.first().totalCents != preview.last().totalCents) {
                                        "，最后一期 ¥${MoneyFormatter.centsToYuan(preview.last().totalCents)}"
                                    } else {
                                        ""
                                    }
                                ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("inst_preview"),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(
                        if (wholeBill) null else pickedTxnId,
                        principal,
                        periods ?: 0,
                        feeCents,
                        feeMode,
                    )
                },
                enabled = periodsOk && principalOk && (wholeBill || pickedTxnId != null),
                modifier = Modifier.testTag("inst_confirm"),
            ) { Text("确认分期") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/**
 * 合并借贷账户（v1.9.0 / PRD 3.4）。
 *
 * 弹窗里直接展示「合并后余额」，因为净值模型下合并的意义就是净值相加：
 * 一个待收 500、一个待还 200，合起来就是待收 300。
 */
@Composable
private fun MergeTargetDialog(
    current: Account?,
    targets: List<Account>,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var selectedId by remember { mutableStateOf(targets.firstOrNull()?.id) }
    val target = targets.firstOrNull { it.id == selectedId }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("合并到其他借贷账户") },
        text = {
            Column {
                Text(
                    text = "当前账户「${current?.name.orEmpty()}」的流水会整体挂到目标账户，然后当前账户进回收站。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                targets.forEach { account ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { selectedId = account.id }
                            .padding(vertical = 8.dp)
                            .testTag("merge_target_${account.id}"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selectedId == account.id, onClick = { selectedId = account.id })
                        Column(Modifier.weight(1f)) {
                            Text(account.name, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                text =
                                    "当前余额 ¥${MoneyFormatter.centsToYuan(kotlin.math.abs(account.balance))}" +
                                        if (account.balance >= 0) "（待收）" else "（待还）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (target != null) {
                    Spacer(Modifier.height(8.dp))
                    val merged = (current?.balance ?: 0L) + target.balance
                    Text(
                        text =
                            "合并后：${target.name} ¥${MoneyFormatter.centsToYuan(kotlin.math.abs(merged))}" +
                                when {
                                    merged > 0 -> "（待收）"
                                    merged < 0 -> "（待还）"
                                    else -> "（已结清）"
                                },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.testTag("merge_preview"),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { selectedId?.let(onConfirm) },
                enabled = selectedId != null,
                modifier = Modifier.testTag("merge_confirm"),
            ) { Text("合并") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
