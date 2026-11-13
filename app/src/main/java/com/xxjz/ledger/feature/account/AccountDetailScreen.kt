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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xxjz.ledger.core.common.DateUtils
import com.xxjz.ledger.core.common.MoneyFormatter
import com.xxjz.ledger.core.designsystem.component.AmountDirection
import com.xxjz.ledger.core.designsystem.component.AmountText
import com.xxjz.ledger.core.designsystem.component.EmptyState
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
