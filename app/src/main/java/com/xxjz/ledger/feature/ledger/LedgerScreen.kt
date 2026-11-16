package com.xxjz.ledger.feature.ledger

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xxjz.ledger.core.common.MoneyFormatter
import com.xxjz.ledger.core.designsystem.component.AmountDirection
import com.xxjz.ledger.core.designsystem.component.AmountText
import com.xxjz.ledger.core.designsystem.component.EmptyState
import com.xxjz.ledger.domain.model.Template
import com.xxjz.ledger.domain.model.TxnType
import com.xxjz.ledger.navigation.AddFab

/**
 * 明细页需要的跳转回调（v1.9.1）。
 *
 * 以前是一个个 `() -> Unit` 参数，到 v1.9.1 已经 11 个，逼近 detekt 的 12 个上限。
 * 打包成一个不可变的数据类：加新跳转只改这里，[LedgerScreen] 的签名不再膨胀，
 * 也让「这个页面能跳到哪去」变成一份可读的清单。
 */
data class LedgerNav(
    val onAdd: () -> Unit = {},
    val onOpenTxn: (String) -> Unit = {},
    val onEditTxn: (String) -> Unit = {},
    val onOpenAccount: (String) -> Unit = {},
    val onOpenRecurring: () -> Unit = {},
    val onUseTemplate: (String) -> Unit = {},
    val onOpenSearch: () -> Unit = {},
    val onOpenCalendar: () -> Unit = {},
    val onOpenBackup: () -> Unit = {},
    val onOpenBudget: () -> Unit = {},
    val onMenu: () -> Unit = {},
)

/** P01 明细首页：月小计 + 按天分组流水（M2/M4 范围）。 */
@Composable
fun LedgerScreen(
    nav: LedgerNav = LedgerNav(),
    viewModel: LedgerViewModel = hiltViewModel(),
) {
    val onAdd = nav.onAdd
    val onOpen = nav.onOpenTxn
    val onOpenAccount = nav.onOpenAccount
    val onOpenRecurring = nav.onOpenRecurring
    val onUseTemplate = nav.onUseTemplate
    val onEdit = nav.onEditTxn
    val onOpenSearch = nav.onOpenSearch
    val onOpenCalendar = nav.onOpenCalendar
    val onOpenBackup = nav.onOpenBackup
    val onOpenBudget = nav.onOpenBudget
    val onMenu = nav.onMenu
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var showMonthPicker by remember { mutableStateOf(false) }
    // 长按 / 左滑 / 批量模式（PRD v1.9.0 第 3 条）
    var menuRow by remember { mutableStateOf<TxnRow?>(null) }
    var selecting by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(emptySet<String>()) }

    LaunchedEffect(Unit) { viewModel.messages.collect { snackbar.showSnackbar(it) } }

    if (showMonthPicker) {
        MonthPickerDialog(
            current = java.time.YearMonth.of(state.monthKey / 100, state.monthKey % 100),
            onPick = { ym ->
                viewModel.selectMonth(ym.year, ym.monthValue)
                showMonthPicker = false
            },
            onDismiss = { showMonthPicker = false },
        )
    }

    Box(Modifier.fillMaxSize().testTag("screen_ledger")) {
        Column(Modifier.fillMaxSize()) {
            MonthHeader(
                label = state.monthLabel,
                onPrevious = viewModel::previousMonth,
                onNext = viewModel::nextMonth,
                onClickLabel = { showMonthPicker = true },
                onSearch = onOpenSearch,
                onCalendar = onOpenCalendar,
                onMenu = onMenu,
            )
            state.backupHint?.let { hint ->
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.secondaryContainer)
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                            .testTag("home_backup_hint"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        hint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onOpenBackup, modifier = Modifier.testTag("home_backup_action")) {
                        Text("去备份")
                    }
                }
            }
            SummaryCard(income = state.income, expense = state.expense)
            if (state.expense > 0) {
                SummaryHint(elapsedDays = state.elapsedDays, dailyAverage = state.dailyAverage)
            }
            if (state.templates.isNotEmpty()) {
                TemplateBar(templates = state.templates, onUse = onUseTemplate)
            }
            if (state.groups.isEmpty() && !state.loading) {
                EmptyState(
                    title = "这个月还没有账目",
                    description = "点击右下角 ＋ 记下第一笔",
                    actionText = "记一笔",
                    onAction = onAdd,
                    modifier = Modifier.weight(1f),
                )
                BudgetOverviewCard(budget = state.budget, onOpen = onOpenBudget)
            } else {
                // 必须用 weight 而不是 fillMaxSize：Column 里未加权的子项拿的是父级最大高度，
                // 不是「剩下多少」。写 fillMaxSize 会让列表比可用空间高出一截，
                // 可视区被挤到只剩四五条，而且滚到底也够不到最后的预算卡（v1.9.5 第 4 条）。
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth().testTag("txn_list"),
                    contentPadding = PaddingValues(bottom = 96.dp),
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
                                        onOpen(row.txn.id)
                                    }
                                },
                                onLongClick = { if (!selecting) menuRow = row },
                            )
                            // 分隔线内缩、不通栏：通栏会把页面切成一条条，内缩才是「条目之间有界限」
                            if (row.txn.id != lastId) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                    thickness = 1.dp,
                                    color = MaterialTheme.colorScheme.outlineVariant,
                                )
                            }
                        }
                    }
                    // 预算概览卡固定在列表最下层（v1.8.0 / PRD F4、D7）
                    item(key = "budget_card") {
                        BudgetOverviewCard(budget = state.budget, onOpen = onOpenBudget)
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
        TxnMenuHost(
            target = menuRow,
            viewModel = viewModel,
            onOpenAccount = onOpenAccount,
            onOpenRecurring = onOpenRecurring,
            onEditTxn = onEdit,
            onStartBatch = { row ->
                selecting = true
                selectedIds = setOf(row.txn.id)
            },
            onDelete = { row ->
                viewModel.delete(row.txn.id)
                menuRow = null
            },
            onDismiss = { menuRow = null },
        )
        // 悬浮「＋」只在明细页出现（PRD D9）；批量模式下藏起来，免得挡住操作栏
        if (!selecting) {
            AddFab(
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 24.dp),
                onClick = onAdd,
            )
        }
        SnackbarHost(hostState = snackbar, modifier = Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun MonthHeader(
    label: String,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onClickLabel: () -> Unit,
    onSearch: () -> Unit,
    onCalendar: () -> Unit,
    onMenu: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onMenu, modifier = Modifier.testTag("menu_entry")) {
            Icon(Icons.Outlined.Menu, contentDescription = "菜单")
        }
        IconButton(onClick = onPrevious, modifier = Modifier.testTag("month_prev")) {
            Icon(Icons.Outlined.ChevronLeft, contentDescription = "上个月")
        }
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f).clickable(onClick = onClickLabel).testTag("month_label"),
        )
        IconButton(onClick = onNext, modifier = Modifier.testTag("month_next")) {
            Icon(Icons.Outlined.ChevronRight, contentDescription = "下个月")
        }
        IconButton(onClick = onCalendar, modifier = Modifier.testTag("calendar_entry")) {
            Icon(Icons.Outlined.CalendarMonth, contentDescription = "日历")
        }
        IconButton(onClick = onSearch, modifier = Modifier.testTag("search_entry")) {
            Icon(Icons.Outlined.Search, contentDescription = "搜索")
        }
    }
}

/** 小结条：已过天数与日均支出，3 秒后自动消失（M4-T3）。 */
@Composable
private fun SummaryHint(
    elapsedDays: Int,
    dailyAverage: Long,
) {
    var visible by remember { mutableStateOf(true) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(3_000)
        visible = false
    }
    androidx.compose.animation.AnimatedVisibility(visible = visible) {
        Text(
            text = "本月已过 $elapsedDays 天 · 日均支出 ¥${MoneyFormatter.centsToYuan(dailyAverage)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 20.dp, bottom = 4.dp).testTag("summary_hint"),
        )
    }
}

/** 模板快捷区：点一下把金额/备注带进记账面板（AC-13）。 */
@Composable
private fun TemplateBar(
    templates: List<Template>,
    onUse: (String) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().testTag("template_bar"),
    ) {
        items(templates, key = { it.id }) { template ->
            Surface(
                onClick = { onUse(template.id) },
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Text(
                    text = "${template.name} ¥${MoneyFormatter.centsToPlain(template.amount)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun SummaryCard(
    income: Long,
    expense: Long,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "本月支出",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "¥${MoneyFormatter.centsToYuan(expense)}",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.testTag("month_expense"),
            )
            Spacer(Modifier.height(12.dp))
            Row {
                SummaryCell("收入", income, AmountDirection.INCOME, Modifier.weight(1f))
                SummaryCell("结余", income - expense, AmountDirection.NEUTRAL, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun SummaryCell(
    title: String,
    amount: Long,
    direction: AmountDirection,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text(title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
        AmountText(
            cents = amount,
            direction = direction,
            signed = false,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun DayHeader(group: DayGroup) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = group.title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = group.subtitle,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text =
                buildString {
                    if (group.expense > 0) append("支 ${MoneyFormatter.centsToYuan(group.expense)}")
                    if (group.income > 0) {
                        if (isNotEmpty()) append("  ")
                        append("收 ${MoneyFormatter.centsToYuan(group.income)}")
                    }
                },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 一条流水（PRD v1.9.0 第 3 条，v1.9.5 第 4 条重排行内信息）。
 *
 * 两种交互：轻点打开详情、长按弹动作面板。删除统一走长按抽屉最底部的红色项。
 *
 * 行内分四个固定位置，扫视时不必逐字读：
 * ```
 * [图标] 餐饮 · 午饭              ¥38.00
 *        12:30                        招行储蓄卡
 * ```
 * 以前分类名、备注、金额挤在一条基线上，一眼抓不到「花了多少、从哪张卡出的」。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TxnItem(
    row: TxnRow,
    selecting: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val txn = row.txn
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                // 必须有背景：列表下面还有预算概览卡，透明会让它透上来
                .background(MaterialTheme.colorScheme.surface)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(horizontal = 16.dp, vertical = 14.dp)
                .testTag("txn_item_${txn.id}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selecting) {
            Checkbox(
                checked = selected,
                onCheckedChange = { onClick() },
                modifier = Modifier.padding(end = 8.dp),
            )
        }
        Box(
            modifier =
                Modifier
                    .size(38.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = row.categoryName.take(1),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = titleOf(row),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (txn.pendingReimbursement) {
                    TinyBadge("待报销", "badge_reimburse_${txn.id}")
                }
                if (txn.excludedFromStats) {
                    TinyBadge("不计入", "badge_excluded_${txn.id}")
                }
                Spacer(Modifier.weight(1f))
                AmountText(
                    cents = txn.amount,
                    direction =
                        when (txn.type) {
                            TxnType.INCOME -> AmountDirection.INCOME
                            TxnType.EXPENSE -> AmountDirection.EXPENSE
                            else -> AmountDirection.NEUTRAL
                        },
                    signed = txn.type != TxnType.TRANSFER,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = row.timeLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = accountLineOf(row),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 主标题：`分类 · 备注`，备注为空就只留分类，不留孤零零的分隔点。 */
private fun titleOf(row: TxnRow): String {
    val note = row.txn.note.trim()
    return if (note.isEmpty()) row.categoryName else "${row.categoryName} · $note"
}

/** 副标题右侧：转账写「A → B」，其余写付款账户名。 */
private fun accountLineOf(row: TxnRow): String {
    if (row.txn.type == TxnType.TRANSFER) {
        val from = row.accountName.ifBlank { "账户" }
        val to = row.toAccountName.orEmpty().ifBlank { "账户" }
        return "$from → $to"
    }
    return row.accountName
}

/** 小标签：待报销 / 不计入。 */
@Composable
private fun TinyBadge(
    text: String,
    tag: String,
) {
    Spacer(Modifier.width(6.dp))
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.testTag(tag),
    )
}

/** 勾选集合的增删。 */
private fun Set<String>.toggle(id: String): Set<String> = if (id in this) this - id else this + id
