package com.xxjz.ledger.feature.account

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xxjz.ledger.core.common.MoneyFormatter
import com.xxjz.ledger.core.designsystem.component.ConfirmDialog
import com.xxjz.ledger.core.designsystem.component.EmptyState
import com.xxjz.ledger.core.designsystem.theme.profitColor
import com.xxjz.ledger.data.local.seed.SeedData
import com.xxjz.ledger.domain.model.Account
import com.xxjz.ledger.domain.model.AccountType

/** P13 账户列表：总资产、资金/信用/借贷三组、隐藏与删除（AC-16 / AC-20 / AC-21）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountListScreen(
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onOpenAssetReport: () -> Unit = {},
    onOpenReimbursement: () -> Unit = {},
    onOpenDebts: () -> Unit = {},
    onMenu: () -> Unit = {},
    /** 作为底部主 Tab 时左上角是 ☰，作为二级页时是返回箭头（PRD D10）。 */
    isRootTab: Boolean = false,
    viewModel: AccountListViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var editing by remember { mutableStateOf<Account?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Account?>(null) }

    LaunchedEffect(Unit) { viewModel.messages.collect { snackbar.showSnackbar(it) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("账户") },
                navigationIcon = {
                    if (isRootTab) {
                        IconButton(onClick = onMenu, modifier = Modifier.testTag("menu_entry")) {
                            Icon(Icons.Outlined.Menu, contentDescription = "菜单")
                        }
                    } else {
                        IconButton(onClick = onBack, modifier = Modifier.testTag("account_back")) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onOpenAssetReport, modifier = Modifier.testTag("account_report")) {
                        Icon(Icons.Outlined.Insights, contentDescription = "资产报表")
                    }
                    IconButton(onClick = { creating = true }, modifier = Modifier.testTag("account_add")) {
                        Icon(Icons.Outlined.Add, contentDescription = "新建账户")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            EntryCards(
                reimbursement = state.reimbursement,
                debts = state.debts,
                onOpenReimbursement = onOpenReimbursement,
                onOpenDebts = onOpenDebts,
            )
            AssetCard(
                assets = state.summary.assets,
                liabilities = state.summary.liabilities,
                net = state.summary.net,
            )
            if (state.groups.isEmpty() && !state.loading) {
                EmptyState(
                    title = "还没有账户",
                    description = "先建一个现金或储蓄卡账户，就能开始记账了",
                    actionText = "新建账户",
                    onAction = { creating = true },
                    modifier = Modifier.fillMaxWidth().height(300.dp),
                )
            }
            state.groups.forEach { group ->
                GroupHeader(group = group, onToggle = { viewModel.toggleGroup(group.type) })
                if (!group.collapsed) {
                    group.accounts.forEach { account ->
                        AccountRow(
                            account = account,
                            onClick = { onOpen(account.id) },
                            onEdit = { editing = account },
                            onToggleHidden = { viewModel.setHidden(account.id, true) },
                            onDelete = { deleting = account },
                        )
                    }
                }
            }
            if (state.hidden.isNotEmpty()) {
                TextButton(
                    onClick = viewModel::toggleShowHidden,
                    modifier = Modifier.padding(horizontal = 8.dp).testTag("toggle_hidden"),
                ) {
                    Text(if (state.showHidden) "收起已隐藏账户" else "已隐藏账户（${state.hidden.size}）")
                }
                if (state.showHidden) {
                    state.hidden.forEach { account ->
                        AccountRow(
                            account = account,
                            onClick = { onOpen(account.id) },
                            onEdit = { editing = account },
                            onToggleHidden = { viewModel.setHidden(account.id, false) },
                            onDelete = { deleting = account },
                            hiddenLabel = true,
                        )
                    }
                }
            }
            Spacer(Modifier.height(40.dp))
        }
    }

    if (creating || editing != null) {
        AccountEditDialog(
            account = editing,
            onDismiss = {
                creating = false
                editing = null
            },
            onSave = { account ->
                viewModel.save(account)
                creating = false
                editing = null
            },
        )
    }

    deleting?.let { account ->
        ConfirmDialog(
            title = "删除账户「${account.name}」？",
            message = "该账户下的账目会一并移入回收站，可保留 30 天。",
            confirmText = "删除",
            destructive = true,
            onConfirm = {
                viewModel.delete(account.id)
                deleting = null
            },
            onDismiss = { deleting = null },
        )
    }
}

/**
 * 待报销 / 借贷台账入口卡（v1.8.0 / PRD F3 / D5 / D6）。
 *
 * 两张并排，数值直接显示在卡里，让用户不点进去也知道「还有多少钱没报销、还欠多少」。
 */
@Composable
private fun EntryCards(
    reimbursement: EntryCardState,
    debts: EntryCardState,
    onOpenReimbursement: () -> Unit,
    onOpenDebts: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        EntryCard(
            title = "待报销",
            value = reimbursement.amountCents,
            subtitle = if (reimbursement.count == 0) "暂无" else "${reimbursement.count} 笔待处理",
            onClick = onOpenReimbursement,
            modifier = Modifier.weight(1f).testTag("entry_reimbursement"),
        )
        EntryCard(
            title = "借贷台账",
            value = debts.amountCents,
            subtitle = if (debts.count == 0) "暂无欠款" else "${debts.count} 笔未结清",
            onClick = onOpenDebts,
            modifier = Modifier.weight(1f).testTag("entry_debts"),
        )
    }
}

@Composable
private fun EntryCard(
    title: String,
    value: Long,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(onClick = onClick, modifier = modifier) {
        Column(Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                text = "¥${MoneyFormatter.centsToYuan(value)}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 2.dp),
            )
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 分组头：标题 + 合计 + 折叠箭头，点击整行折叠/展开（v1.8.0 / PRD F3）。 */
@Composable
private fun GroupHeader(
    group: AccountGroup,
    onToggle: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp)
                .testTag("group_${group.type.name}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = group.title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "¥${MoneyFormatter.centsToYuan(group.total)}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Icon(
            imageVector = if (group.collapsed) Icons.Outlined.ExpandMore else Icons.Outlined.ExpandLess,
            contentDescription = if (group.collapsed) "展开" else "折叠",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp).size(18.dp),
        )
    }
}

@Composable
private fun AssetCard(
    assets: Long,
    liabilities: Long,
    net: Long,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("净资产", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(
                text = "¥${MoneyFormatter.centsToYuan(net)}",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.testTag("net_assets"),
            )
            Spacer(Modifier.height(10.dp))
            Row {
                Column(Modifier.weight(1f)) {
                    Text("总资产", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text("¥${MoneyFormatter.centsToYuan(assets)}", style = MaterialTheme.typography.bodyMedium)
                }
                Column(Modifier.weight(1f)) {
                    Text("总负债", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text("¥${MoneyFormatter.centsToYuan(liabilities)}", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun AccountRow(
    account: Account,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onToggleHidden: () -> Unit,
    onDelete: () -> Unit,
    hiddenLabel: Boolean = false,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .size(34.dp)
                    .background(
                        runCatching { Color(android.graphics.Color.parseColor(account.color)) }
                            .getOrElse { MaterialTheme.colorScheme.surfaceVariant },
                        CircleShape,
                    ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                account.name.take(1),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
            )
        }
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(account.name, style = MaterialTheme.typography.bodyLarge)
                if (hiddenLabel) {
                    Text(
                        "已隐藏",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
            Text(
                text = typeLabel(account.type),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = "¥${MoneyFormatter.centsToYuan(account.balance)}",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
        )
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Outlined.MoreVert, contentDescription = "更多", tint = LocalContentColor.current)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("编辑") },
                    onClick = {
                        menu = false
                        onEdit()
                    },
                )
                DropdownMenuItem(
                    text = { Text(if (hiddenLabel) "取消隐藏" else "隐藏") },
                    onClick = {
                        menu = false
                        onToggleHidden()
                    },
                )
                DropdownMenuItem(
                    text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                    onClick = {
                        menu = false
                        onDelete()
                    },
                )
            }
        }
    }
}

private fun typeLabel(type: AccountType): String =
    when (type) {
        AccountType.CASH -> "现金"
        AccountType.SAVINGS -> "储蓄卡"
        AccountType.CREDIT -> "信用"
        AccountType.DEBT -> "借贷"
        AccountType.INVEST -> "投资"
    }

/** P15 账户编辑：按类型驱动表单，信用类才显示额度与账单/还款日（AC-17）。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AccountEditDialog(
    account: Account?,
    onDismiss: () -> Unit,
    onSave: (Account) -> Unit,
) {
    var name by remember { mutableStateOf(account?.name.orEmpty()) }
    var type by remember { mutableStateOf(account?.type ?: AccountType.CASH) }
    var balance by remember {
        mutableStateOf(account?.let { MoneyFormatter.centsToPlain(it.initialBalance) } ?: "0")
    }
    var limit by remember { mutableStateOf(account?.cardLimit?.let { MoneyFormatter.centsToPlain(it) }.orEmpty()) }
    var billDay by remember { mutableStateOf(account?.billDay?.toString().orEmpty()) }
    var repayDay by remember { mutableStateOf(account?.repayDay?.toString().orEmpty()) }
    var color by remember { mutableStateOf(account?.color ?: "#2E7D5B") }
    // v1.8.0 投资账户专有：累计成本、当前市值、是否计入净资产（PRD F2）
    var cost by remember {
        mutableStateOf(account?.let { MoneyFormatter.centsToPlain(it.costCents) } ?: "0")
    }
    var marketValue by remember {
        mutableStateOf(account?.let { MoneyFormatter.centsToPlain(it.marketValueCents) } ?: "0")
    }
    var includeInNetAssets by remember { mutableStateOf(account?.includeInNetAssets ?: true) }
    val initialCents = MoneyFormatter.yuanToCents(balance) ?: 0L

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (account == null) "新建账户" else "编辑账户") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(12) },
                    label = { Text("名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("account_name"),
                )
                Spacer(Modifier.height(8.dp))
                // 5 个类型放不下单行：360dp 屏第 5 个「投资」会被裁到屏幕外，
                // 用户看到的就是「没有投资账户」。用 FlowRow 自动换行（v1.8.1）
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AccountType.entries.forEach { item ->
                        FilterChip(
                            selected = type == item,
                            onClick = { type = item },
                            label = { Text(typeLabel(item)) },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = balance,
                    onValueChange = { balance = it.filter { c -> c.isDigit() || c == '-' || c == '.' }.take(14) },
                    label = { Text(if (account == null) "初始余额" else "初始余额（改后余额会重算）") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth().testTag("account_balance"),
                )
                if (type == AccountType.CREDIT) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = limit,
                        onValueChange = { limit = it.filter { c -> c.isDigit() || c == '.' }.take(14) },
                        label = { Text("额度") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = billDay,
                            onValueChange = { billDay = it.filter(Char::isDigit).take(2) },
                            label = { Text("账单日") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = repayDay,
                            onValueChange = { repayDay = it.filter(Char::isDigit).take(2) },
                            label = { Text("还款日") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                if (type == AccountType.INVEST) {
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = cost,
                            onValueChange = { cost = it.filter { c -> c.isDigit() || c == '.' }.take(14) },
                            label = { Text("累计成本") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(1f).testTag("account_cost"),
                        )
                        OutlinedTextField(
                            value = marketValue,
                            onValueChange = { marketValue = it.filter { c -> c.isDigit() || c == '.' }.take(14) },
                            label = { Text("当前市值") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(1f).testTag("account_market"),
                        )
                    }
                    val costCents = MoneyFormatter.yuanToCents(cost) ?: 0L
                    val marketCents = MoneyFormatter.yuanToCents(marketValue) ?: 0L
                    Text(
                        "盈亏 ${MoneyFormatter.centsToYuan(marketCents - costCents, showSymbol = true)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = profitColor(marketCents - costCents),
                        modifier = Modifier.padding(top = 4.dp).testTag("account_profit_preview"),
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("计入净资产", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Switch(
                            checked = includeInNetAssets,
                            onCheckedChange = { includeInNetAssets = it },
                            modifier = Modifier.testTag("account_include_switch"),
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text("颜色", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 6.dp)) {
                    listOf("#2E7D5B", "#3A6EA5", "#B4553C", "#8A5CB8", "#C79A2E", "#5A6B72").forEach { c ->
                        Box(
                            modifier =
                                Modifier
                                    .size(28.dp)
                                    .background(Color(android.graphics.Color.parseColor(c)), CircleShape)
                                    .clickable { color = c },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (color == c) {
                                Text("✓", color = Color.White, style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        Account(
                            id = account?.id ?: SeedData.newId("acc"),
                            name = name.trim(),
                            type = type,
                            initialBalance = initialCents,
                            icon = account?.icon.orEmpty(),
                            color = color,
                            cardLimit = limit.toBigDecimalOrNullCents(),
                            billDay = billDay.toIntOrNull(),
                            repayDay = repayDay.toIntOrNull(),
                            hidden = account?.hidden ?: false,
                            sortOrder = account?.sortOrder ?: 99,
                            balance = account?.balance ?: 0,
                            costCents = if (type == AccountType.INVEST) MoneyFormatter.yuanToCents(cost) ?: 0L else 0L,
                            marketValueCents = if (type == AccountType.INVEST) MoneyFormatter.yuanToCents(marketValue) ?: 0L else 0L,
                            includeInNetAssets = includeInNetAssets,
                        ),
                    )
                },
                enabled = name.isNotBlank(),
                modifier = Modifier.testTag("account_save"),
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private fun String.toBigDecimalOrNullCents(): Long? = MoneyFormatter.yuanToCents(this)
