package com.xxjz.ledger.feature.account

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xxjz.ledger.core.common.DateUtils
import com.xxjz.ledger.core.common.MoneyFormatter
import com.xxjz.ledger.core.designsystem.component.AmountDirection
import com.xxjz.ledger.core.designsystem.component.AmountText
import com.xxjz.ledger.core.designsystem.component.EmptyState
import com.xxjz.ledger.core.designsystem.theme.profitColor
import com.xxjz.ledger.domain.model.Account
import com.xxjz.ledger.domain.model.AccountType
import com.xxjz.ledger.domain.model.TxnType

/** P14 账户详情：余额、本月收支、笔数、该账户流水；信用类显示额度进度（AC-18 / AC-24）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountDetailScreen(
    onBack: () -> Unit,
    onOpenTxn: (String) -> Unit,
    viewModel: AccountDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var editingMarket by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.account?.name ?: "账户详情") },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("account_detail_back")) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Card(Modifier.fillMaxWidth().padding(16.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        if (state.account?.type == AccountType.CREDIT) "当前欠款" else "当前余额",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "¥${MoneyFormatter.centsToYuan(state.account?.balance ?: 0)}",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
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
                                onEditMarketValue = { editingMarket = true },
                                onToggleInclude = viewModel::setIncludeInNetAssets,
                            )
                        }
                    if (state.account?.type == AccountType.CREDIT && state.account?.cardLimit != null) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "可用额度 ¥${MoneyFormatter.centsToYuan(state.availableLimit ?: 0)} / " +
                                "¥${MoneyFormatter.centsToYuan(state.account?.cardLimit ?: 0)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        state.account?.repayDay?.let {
                            Text("还款日：每月 $it 日", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

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
                                Text(row.categoryName, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    DateUtils.friendlyDate(DateUtils.toDate(row.txn.dateTime)) +
                                        " · " + row.txn.note.ifBlank { typeText(row.txn.type) },
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
