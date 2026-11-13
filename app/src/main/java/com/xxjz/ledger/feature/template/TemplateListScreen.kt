package com.xxjz.ledger.feature.template

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xxjz.ledger.core.common.MoneyFormatter
import com.xxjz.ledger.core.designsystem.component.ConfirmDialog
import com.xxjz.ledger.core.designsystem.component.EmptyState
import com.xxjz.ledger.domain.model.Template
import com.xxjz.ledger.domain.model.TxnType

/** P05/P06 模板管理：列表 + 新建/编辑弹窗 + 一键带入（AC-13）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TemplateListScreen(
    onBack: () -> Unit,
    onUse: (String) -> Unit,
    viewModel: TemplateViewModel = hiltViewModel(),
) {
    val templates by viewModel.templates.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Template?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Template?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("记账模板") },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("template_back")) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { creating = true }, modifier = Modifier.testTag("template_add")) {
                        Icon(Icons.Outlined.Add, contentDescription = "新建模板")
                    }
                },
            )
        },
    ) { padding ->
        if (templates.isEmpty()) {
            EmptyState(
                title = "还没有模板",
                description = "把常买的早餐、通勤车费存成模板，记账时一键带入",
                actionText = "新建模板",
                onAction = { creating = true },
                modifier = Modifier.fillMaxSize().padding(padding),
            )
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(templates, key = { it.id }) { template ->
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable { onUse(template.id) }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(template.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text =
                                    "${if (template.type == TxnType.INCOME) "收入" else "支出"} · " +
                                        "¥${MoneyFormatter.centsToYuan(template.amount)}" +
                                        if (template.note.isNotBlank()) " · ${template.note}" else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { editing = template }) { Text("编辑") }
                        TextButton(onClick = { deleting = template }) {
                            Text("删除", color = MaterialTheme.colorScheme.error)
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }

    if (creating || editing != null) {
        TemplateEditDialog(
            template = editing,
            onDismiss = {
                creating = false
                editing = null
            },
            onConfirm = { name, amount, type, note ->
                viewModel.save(editing?.id, name, amount, type, note)
                creating = false
                editing = null
            },
        )
    }

    deleting?.let { template ->
        ConfirmDialog(
            title = "删除模板「${template.name}」？",
            message = "已记的账目不受影响。",
            confirmText = "删除",
            destructive = true,
            onConfirm = {
                viewModel.delete(template.id)
                deleting = null
            },
            onDismiss = { deleting = null },
        )
    }
}

@Composable
private fun TemplateEditDialog(
    template: Template?,
    onDismiss: () -> Unit,
    onConfirm: (String, Long, TxnType, String) -> Unit,
) {
    var name by remember { mutableStateOf(template?.name.orEmpty()) }
    var amount by remember {
        mutableStateOf(template?.let { MoneyFormatter.centsToPlain(it.amount) } ?: "")
    }
    var note by remember { mutableStateOf(template?.note.orEmpty()) }
    var type by remember { mutableStateOf(template?.type ?: TxnType.EXPENSE) }
    val cents = MoneyFormatter.yuanToCents(amount)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (template == null) "新建模板" else "编辑模板") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(20) },
                    label = { Text("名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("template_name"),
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = type == TxnType.EXPENSE,
                        onClick = { type = TxnType.EXPENSE },
                        label = { Text("支出") },
                    )
                    FilterChip(
                        selected = type == TxnType.INCOME,
                        onClick = { type = TxnType.INCOME },
                        label = { Text("收入") },
                    )
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it.filter { c -> c.isDigit() || c == '.' }.take(12) },
                    label = { Text("金额") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth().testTag("template_amount"),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it.take(200) },
                    label = { Text("备注（可选）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name, cents ?: 0L, type, note) },
                enabled = name.isNotBlank() && cents != null && cents > 0,
                modifier = Modifier.testTag("template_save"),
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
