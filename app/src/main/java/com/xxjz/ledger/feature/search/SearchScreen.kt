package com.xxjz.ledger.feature.search

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xxjz.ledger.core.common.DateUtils
import com.xxjz.ledger.core.common.MoneyFormatter
import com.xxjz.ledger.core.designsystem.component.AmountDirection
import com.xxjz.ledger.core.designsystem.component.AmountText
import com.xxjz.ledger.core.designsystem.component.EmptyState
import com.xxjz.ledger.domain.model.TxnType

/** P03 搜索：关键词 + 类型/时间/金额筛选，实时显示「共 X 笔 合计 ¥Y」（AC-28 ~ AC-31）。 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SearchScreen(
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { TopAppBar(title = { Text("搜索") }) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack, modifier = Modifier.testTag("search_back")) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                }
                OutlinedTextField(
                    value = state.query,
                    onValueChange = viewModel::setQuery,
                    placeholder = { Text("搜索备注、商家") },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    trailingIcon = {
                        if (state.query.isNotEmpty()) {
                            IconButton(onClick = { viewModel.setQuery("") }) {
                                Icon(Icons.Outlined.Close, contentDescription = "清空")
                            }
                        }
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { viewModel.submitQuery() }),
                    modifier = Modifier.weight(1f).testTag("search_input"),
                )
            }

            LazyRow(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    FilterChip(
                        selected = state.type == null,
                        onClick = { viewModel.setType(null) },
                        label = { Text("全部") },
                    )
                }
                item {
                    FilterChip(
                        selected = state.type == TxnType.EXPENSE,
                        onClick = { viewModel.setType(TxnType.EXPENSE) },
                        label = { Text("支出") },
                    )
                }
                item {
                    FilterChip(
                        selected = state.type == TxnType.INCOME,
                        onClick = { viewModel.setType(TxnType.INCOME) },
                        label = { Text("收入") },
                    )
                }
                items(SearchRangeOption.entries) { option ->
                    FilterChip(
                        selected = state.range == option,
                        onClick = { viewModel.setRange(option) },
                        label = { Text(option.label) },
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = state.minText,
                    onValueChange = viewModel::setMin,
                    placeholder = { Text("最低金额") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = state.maxText,
                    onValueChange = viewModel::setMax,
                    placeholder = { Text("最高金额") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
            }

            if (state.query.isBlank() && state.history.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("最近搜索", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = viewModel::clearHistory) { Text("清空") }
                }
                LazyRow(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.history) { keyword ->
                        FilterChip(
                            selected = false,
                            onClick = {
                                viewModel.setQuery(keyword)
                                viewModel.submitQuery()
                            },
                            label = { Text(keyword) },
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "共 ${state.count} 笔 · 合计 ¥${MoneyFormatter.centsToYuan(state.totalCents)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("search_summary"),
                )
            }

            if (state.results.isEmpty()) {
                EmptyState(
                    title = "没有找到相关账目",
                    description = "换个关键词，或放宽筛选条件试试",
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(state.results, key = { it.txn.id }) { row ->
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
                                    text =
                                        DateUtils.toDate(row.txn.dateTime).let {
                                            "${it.monthValue}月${it.dayOfMonth}日"
                                        } + if (row.txn.note.isNotBlank()) " · ${row.txn.note}" else "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
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
