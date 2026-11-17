package com.xxjz.ledger.feature.category

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xxjz.ledger.core.designsystem.component.ConfirmDialog
import com.xxjz.ledger.core.designsystem.component.EmptyState
import com.xxjz.ledger.domain.model.Category
import com.xxjz.ledger.domain.model.TxnType

/** 分类 / 标签 / 商家管理页（AC-79）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryManageScreen(
    onBack: () -> Unit,
    viewModel: CategoryManageViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var editor by remember { mutableStateOf<EditorTarget?>(null) }
    // 标签 / 商家的编辑目标：id 为空表示新建
    var editingKey by remember { mutableStateOf<String?>(null) }
    var editingName by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("分类与标签") },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("manage_back")) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        floatingActionButton = {
            androidx.compose.material3.ExtendedFloatingActionButton(
                onClick = {
                    editor =
                        when (state.tab) {
                            ManageTab.CATEGORY -> EditorTarget.Category(parentId = null)
                            ManageTab.TAG -> EditorTarget.Tag
                            ManageTab.MERCHANT -> EditorTarget.Merchant
                        }
                },
                modifier = Modifier.testTag("manage_add"),
            ) {
                Icon(Icons.Outlined.Add, contentDescription = null)
                Text("新增")
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).testTag("screen_manage")) {
            TabRow(selectedTabIndex = state.tab.ordinal) {
                ManageTab.entries.forEach { tab ->
                    Tab(
                        selected = state.tab == tab,
                        onClick = { viewModel.selectTab(tab) },
                        text = { Text(tab.label) },
                        modifier = Modifier.testTag("manage_tab_${tab.name}"),
                    )
                }
            }
            if (state.tab == ManageTab.CATEGORY) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    TxnType.entries.filter { it == TxnType.EXPENSE || it == TxnType.INCOME }.forEach { type ->
                        FilterChip(
                            selected = state.type == type,
                            onClick = { viewModel.selectType(type) },
                            label = { Text(if (type == TxnType.EXPENSE) "支出" else "收入") },
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    }
                }
            }

            when (state.tab) {
                ManageTab.CATEGORY ->
                    CategoryList(
                        state = state,
                        onToggleHidden = viewModel::toggleHidden,
                        onDelete = viewModel::requestDelete,
                        onAddChild = { parent -> editor = EditorTarget.Category(parentId = parent.id) },
                        onEdit = { c -> editor = EditorTarget.Category(parentId = c.parentId, id = c.id, name = c.name) },
                    )
                ManageTab.TAG ->
                    SimpleList(
                        rows = state.tags.map { it.id to it.name },
                        empty = "还没有标签",
                        onDelete = { id -> state.tags.firstOrNull { it.id == id }?.let(viewModel::deleteTag) },
                        onEdit = { id, name ->
                            editingKey = id
                            editingName = name
                            editor = EditorTarget.Tag
                        },
                    )
                ManageTab.MERCHANT ->
                    SimpleList(
                        rows = state.merchants.map { it.id to it.name },
                        empty = "还没有商家",
                        onDelete = { id -> state.merchants.firstOrNull { it.id == id }?.let(viewModel::deleteMerchant) },
                        onEdit = { id, name ->
                            editingKey = id
                            editingName = name
                            editor = EditorTarget.Merchant
                        },
                    )
            }
        }
    }

    if (state.pendingDeleteCategoryId != null) {
        ConfirmDialog(
            title = "删除分类",
            message = "删除后该分类下的历史账目会变成「未分类」，确定删除吗？",
            confirmText = "删除",
            destructive = true,
            onConfirm = { viewModel.confirmDelete() },
            onDismiss = viewModel::dismissDelete,
        )
    }

    state.message?.let { text ->
        AlertDialog(
            onDismissRequest = viewModel::consumeMessage,
            confirmButton = {
                TextButton(onClick = viewModel::consumeMessage) { Text("好") }
            },
            text = { Text(text, modifier = Modifier.testTag("manage_message")) },
        )
    }

    editor?.let { target ->
        NameEditorDialog(
            title =
                when (target) {
                    is EditorTarget.Category -> if (target.id == null) "新建分类" else "重命名分类"
                    EditorTarget.Tag -> if (editingKey == null) "新建标签" else "重命名标签"
                    EditorTarget.Merchant -> if (editingKey == null) "新建商家" else "重命名商家"
                },
            initial = if (target is EditorTarget.Category) target.name else editingName,
            onDismiss = {
                editor = null
                editingKey = null
                editingName = ""
            },
            onConfirm = { name ->
                when (target) {
                    is EditorTarget.Category -> viewModel.saveCategory(target.id, name, target.parentId)
                    EditorTarget.Tag -> viewModel.saveTag(editingKey, name)
                    EditorTarget.Merchant -> viewModel.saveMerchant(editingKey, name)
                }
                editor = null
                editingKey = null
                editingName = ""
            },
        )
    }
}

/** 编辑弹窗的目标；分类需要带 parentId。 */
private sealed interface EditorTarget {
    data class Category(
        val parentId: String?,
        val id: String? = null,
        val name: String = "",
    ) : EditorTarget

    data object Tag : EditorTarget

    data object Merchant : EditorTarget
}

@Composable
private fun CategoryList(
    state: ManageState,
    onToggleHidden: (Category) -> Unit,
    onDelete: (Category) -> Unit,
    onAddChild: (Category) -> Unit,
    onEdit: (Category) -> Unit,
) {
    if (state.groups.isEmpty()) {
        EmptyState(title = "还没有分类", description = "点右下角「新增」建一个大类，再给它加小类")
        return
    }
    LazyColumn(Modifier.fillMaxSize().testTag("manage_categories")) {
        state.groups.forEach { group ->
            item(key = group.parent.id) {
                CategoryRow(group.parent, isChild = false, onToggleHidden, onDelete, onEdit) { onAddChild(group.parent) }
            }
            items(group.children, key = { it.id }) { child ->
                CategoryRow(child, isChild = true, onToggleHidden, onDelete, onEdit, onAddChild = null)
            }
        }
    }
}

@Composable
private fun CategoryRow(
    category: Category,
    isChild: Boolean,
    onToggleHidden: (Category) -> Unit,
    onDelete: (Category) -> Unit,
    onEdit: (Category) -> Unit,
    onAddChild: (() -> Unit)?,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable { onEdit(category) }
                .padding(start = if (isChild) 40.dp else 20.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            category.name,
            style = if (isChild) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
            color =
                if (category.hidden) {
                    MaterialTheme.colorScheme.outline
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
        )
        if (category.hidden) {
            Spacer(Modifier.size(6.dp))
            Text("已隐藏", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
        Spacer(Modifier.weight(1f))
        if (onAddChild != null) {
            IconButton(onClick = onAddChild) { Icon(Icons.Outlined.Add, contentDescription = "添加子分类") }
        }
        IconButton(onClick = { onToggleHidden(category) }) {
            Icon(
                if (category.hidden) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                contentDescription = if (category.hidden) "恢复显示" else "隐藏",
            )
        }
        IconButton(onClick = { onDelete(category) }) { Icon(Icons.Outlined.Delete, contentDescription = "删除") }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun SimpleList(
    rows: List<Pair<String, String>>,
    empty: String,
    onDelete: (String) -> Unit,
    onEdit: (String, String) -> Unit,
) {
    if (rows.isEmpty()) {
        EmptyState(title = empty, description = "新增后会出现在记账页的选择里")
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(rows, key = { it.first }) { (id, name) ->
            Row(
                Modifier.fillMaxWidth().clickable { onEdit(id, name) }.padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(name, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { onDelete(id) }) { Icon(Icons.Outlined.Delete, contentDescription = "删除") }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun NameEditorDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(12) },
                label = { Text("名称") },
                singleLine = true,
                modifier = Modifier.testTag("manage_name_field"),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
