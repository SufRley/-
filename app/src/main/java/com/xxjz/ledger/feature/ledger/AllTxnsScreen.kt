package com.xxjz.ledger.feature.ledger

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xxjz.ledger.core.designsystem.component.EmptyState

/**
 * 全部明细页（v1.9.6 第 2 条）。
 *
 * 首页只放最近四笔之后，完整清单要有地方去。这里保留 v1.9.5 的按天分组与日小计，
 * 行内次行只写 `HH:mm · 账户`——日期已经在组头，再写一遍是重复。
 *
 * 复用 [LedgerViewModel]：同一套分组与聚合，只是 UI 不同。新路由会新建一个实例，
 * 月份状态天然与首页独立，翻到别的月份不会把首页一起带走。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AllTxnsScreen(
    initialMonthKey: Int,
    onBack: () -> Unit,
    onOpenTxn: (String) -> Unit,
    onEditTxn: (String) -> Unit,
    onOpenAccount: (String) -> Unit,
    onOpenRecurring: () -> Unit,
    onOpenSplit: (String) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenCalendar: () -> Unit,
    viewModel: LedgerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var menuRow by remember { mutableStateOf<TxnRow?>(null) }
    var selecting by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(emptySet<String>()) }

    // 从首页进来要停在首页正在看的那个月；不传（<= 0）就用当前月
    LaunchedEffect(initialMonthKey) {
        if (initialMonthKey > 0) viewModel.selectMonth(initialMonthKey / 100, initialMonthKey % 100)
    }
    LaunchedEffect(Unit) { viewModel.messages.collect { snackbar.showSnackbar(it) } }

    BatchBackHandler(enabled = selecting) {
        selecting = false
        selectedIds = emptySet()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ChevronTap(testTag = "all_txns_prev", description = "上个月", onClick = viewModel::previousMonth) {
                            Icon(Icons.Outlined.ChevronLeft, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                        Text(
                            text = state.monthLabel,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(horizontal = 4.dp).testTag("all_txns_month"),
                        )
                        ChevronTap(testTag = "all_txns_next", description = "下个月", onClick = viewModel::nextMonth) {
                            Icon(Icons.Outlined.ChevronRight, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("all_txns_back")) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = onOpenCalendar, modifier = Modifier.testTag("all_txns_calendar")) {
                        Icon(Icons.Outlined.CalendarMonth, contentDescription = "日历")
                    }
                    IconButton(onClick = onOpenSearch, modifier = Modifier.testTag("all_txns_search")) {
                        Icon(Icons.Outlined.Search, contentDescription = "搜索")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { inner ->
        Box(Modifier.fillMaxSize().padding(inner).testTag("screen_all_txns")) {
            Column(Modifier.fillMaxSize()) {
                if (state.groups.isEmpty() && !state.loading) {
                    EmptyState(
                        title = "这个月还没有账目",
                        description = "回到首页记一笔",
                        modifier = Modifier.weight(1f),
                    )
                } else if (state.loading) {
                    // 与首页同理：加载中不要渲染只含预算卡的列表，否则数据插到前面时整列会被顶下去
                    Box(Modifier.weight(1f).fillMaxWidth())
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxWidth().testTag("all_txns_list"),
                        contentPadding = PaddingValues(bottom = 24.dp),
                    ) {
                        state.groups.forEach { group ->
                            item(key = "head_${group.dateKey}") { DayHeader(group) }
                            val lastId = group.rows.lastOrNull()?.txn?.id
                            items(group.rows, key = { it.txn.id }) { row ->
                                TxnItem(
                                    row = row,
                                    selecting = selecting,
                                    selected = row.txn.id in selectedIds,
                                    onClick = {
                                        if (selecting) {
                                            selectedIds = selectedIds.toggle(row.txn.id)
                                        } else {
                                            onOpenTxn(row.txn.id)
                                        }
                                    },
                                    onLongClick = { if (!selecting) menuRow = row },
                                )
                                if (row.txn.id != lastId) {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(horizontal = 16.dp),
                                        thickness = 1.dp,
                                        color = MaterialTheme.colorScheme.outlineVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (selecting) {
                BatchActionBar(
                    count = selectedIds.size,
                    onCancel = {
                        selecting = false
                        selectedIds = emptySet()
                    },
                    onReimburse = {
                        val chosen = state.groups.flatMap { it.rows }.filter { it.txn.id in selectedIds }
                        viewModel.batchReimburse(chosen.map { it.txn })
                        selecting = false
                        selectedIds = emptySet()
                    },
                    onDelete = {
                        viewModel.batchDelete(selectedIds)
                        selecting = false
                        selectedIds = emptySet()
                    },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }

    TxnMenuHost(
        target = menuRow,
        viewModel = viewModel,
        onOpenAccount = onOpenAccount,
        onOpenRecurring = onOpenRecurring,
        onEditTxn = onEditTxn,
        onStartBatch = { row ->
            selecting = true
            selectedIds = setOf(row.txn.id)
        },
        onDelete = { row ->
            viewModel.delete(row.txn.id)
            menuRow = null
        },
        onOpenSplit = onOpenSplit,
        onDismiss = { menuRow = null },
    )
}

/**
 * 月份切换的小按钮。
 *
 * 不用 IconButton：它被 Material 强制撑到 48dp，两个加起来 96dp，加上标题会把
 * 360dp 宽的标题栏挤爆。这里 32dp 的触摸目标在标题栏里够用，也不会把月份文字压成省略号。
 */
@Composable
private fun ChevronTap(
    testTag: String,
    description: String,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .size(32.dp)
                .clip(CircleShape)
                .clickable(onClickLabel = description, onClick = onClick)
                .testTag(testTag),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}
