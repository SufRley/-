package com.xxjz.ledger.feature.budget

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xxjz.ledger.core.designsystem.component.ConfirmDialog
import com.xxjz.ledger.domain.model.BudgetPeriod
import com.xxjz.ledger.domain.model.BudgetScope

/** P11 预算编辑（AC-44 / AC-45）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BudgetEditScreen(
    onBack: () -> Unit,
    viewModel: BudgetEditViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val confirm = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }

    LaunchedEffect(state.finished) { if (state.finished) onBack() }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(if (state.isNew) "新建预算" else "编辑预算") },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("budget_edit_back")) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(
                        onClick = viewModel::save,
                        enabled = state.canSave,
                        modifier = Modifier.testTag("budget_edit_save"),
                    ) { Text("保存") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .testTag("screen_budget_edit"),
        ) {
            OutlinedTextField(
                value = state.name,
                onValueChange = viewModel::setName,
                label = { Text("名称（可留空）") },
                placeholder = { Text(if (state.scope == BudgetScope.TOTAL) "总预算" else "分类预算") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(16.dp).testTag("budget_name_input"),
            )

            SettingRow(label = "周期") {
                FilterChip(
                    selected = state.period == BudgetPeriod.MONTH,
                    onClick = { viewModel.setPeriod(BudgetPeriod.MONTH) },
                    label = { Text("按月") },
                    modifier = Modifier.testTag("period_month"),
                )
                Spacer(Modifier.size(8.dp))
                FilterChip(
                    selected = state.period == BudgetPeriod.YEAR,
                    onClick = { viewModel.setPeriod(BudgetPeriod.YEAR) },
                    label = { Text("按年") },
                    modifier = Modifier.testTag("period_year"),
                )
            }

            OutlinedTextField(
                value = state.amountText,
                onValueChange = viewModel::setAmount,
                label = { Text("预算金额（元）") },
                singleLine = true,
                isError = state.error != null && state.amountCents == null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("budget_amount_input"),
            )

            SettingRow(label = "范围") {
                FilterChip(
                    selected = state.scope == BudgetScope.TOTAL,
                    onClick = { viewModel.setScope(BudgetScope.TOTAL) },
                    label = { Text("全部支出") },
                    modifier = Modifier.testTag("scope_total"),
                )
                Spacer(Modifier.size(8.dp))
                FilterChip(
                    selected = state.scope == BudgetScope.CATEGORY,
                    onClick = { viewModel.setScope(BudgetScope.CATEGORY) },
                    label = { Text("按类别") },
                    modifier = Modifier.testTag("scope_category"),
                )
            }

            if (state.scope == BudgetScope.CATEGORY) {
                CategorySelector(
                    categories = state.categories,
                    selected = state.selected,
                    onToggle = viewModel::toggleCategory,
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(vertical = 8.dp))

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("启用", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "停用后不计入总预算，历史统计不受影响",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = state.enabled,
                    onCheckedChange = { viewModel.toggleEnabled() },
                    modifier = Modifier.testTag("budget_enabled_switch"),
                )
            }

            Text(
                text = "生效月份：${state.monthLabel}（只改这个月及以后，历史月份不受影响）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )

            state.error?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp).testTag("budget_edit_error"),
                )
            }

            if (!state.isNew) {
                TextButton(
                    onClick = { confirm.value = true },
                    modifier = Modifier.padding(16.dp).testTag("budget_delete"),
                ) {
                    Text("删除预算", color = MaterialTheme.colorScheme.error)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirm.value) {
        ConfirmDialog(
            title = "删除预算？",
            message = "会进回收站，可在回收站恢复。",
            onConfirm = {
                confirm.value = false
                viewModel.delete()
            },
            onDismiss = { confirm.value = false },
        )
    }
}

@Composable
private fun SettingRow(
    label: String,
    content: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        content()
    }
}

/** 分类多选：大类一行，子类跟在其后，按同一份树展开。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategorySelector(
    categories: List<Pair<com.xxjz.ledger.domain.model.Category, List<com.xxjz.ledger.domain.model.Category>>>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        categories.forEach { (parent, children) ->
            Text(
                text = parent.name,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = parent.id in selected,
                    onClick = { onToggle(parent.id) },
                    label = { Text("全部${parent.name}") },
                    modifier = Modifier.testTag("cat_${parent.id}"),
                )
                children.forEach { child ->
                    FilterChip(
                        selected = child.id in selected,
                        onClick = { onToggle(child.id) },
                        label = { Text(child.name) },
                        modifier = Modifier.testTag("cat_${child.id}"),
                    )
                }
            }
        }
    }
}
