package com.xxjz.ledger.feature.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xxjz.ledger.core.common.MoneyFormatter
import com.xxjz.ledger.core.designsystem.component.CategoryAvatar
import com.xxjz.ledger.domain.model.Template

/** 模板超过这个数量才出现搜索框，否则一屏就看得完，多一个输入框只是噪音。 */
private const val TEMPLATE_SEARCH_THRESHOLD = 10

/**
 * 记账模板弹层（PRD v1.9.3 第 7 条）。
 *
 * 为什么是底部弹层而不是跳模板页：跳页会**丢掉当前已填的金额、备注、照片**。
 * 用户点了爱心才发现没有想要的模板，返回后一切归零——弹层原地覆盖，关掉就回到原状。
 *
 * 模板页仍然保留在抽屉里，负责「增删改」；这里只负责「用」。分工清楚了，
 * 用户在两个地方看到的操作也不会打架。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EditorTemplateSheet(
    templates: List<Template>,
    categoryOf: (String?) -> Pair<String, String>?,
    onPick: (String) -> Unit,
    onManage: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var keyword by remember { mutableStateOf("") }
    val filtered =
        if (keyword.isBlank()) {
            templates
        } else {
            templates.filter { it.name.contains(keyword.trim(), ignoreCase = true) }
        }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("template_sheet")) {
            Text(
                text = "记账模板",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            if (templates.size > TEMPLATE_SEARCH_THRESHOLD) {
                OutlinedTextField(
                    value = keyword,
                    onValueChange = { keyword = it.take(20) },
                    placeholder = { Text("搜索模板") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().testTag("template_search"),
                )
                Spacer(Modifier.height(8.dp))
            }
            if (templates.isEmpty()) {
                // 空态必须说清「怎么才会有模板」，否则用户只会觉得这个爱心点了没用
                Text(
                    text = "还没有模板。记一笔后长按明细里的账目，可以「存为模板」。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp).testTag("template_empty"),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(filtered, key = { it.id }) { template ->
                        val category = categoryOf(template.categoryId)
                        Surface(
                            onClick = { onPick(template.id) },
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.fillMaxWidth().testTag("template_row_${template.id}"),
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CategoryAvatar(name = category?.first, rawColor = category?.second.orEmpty())
                                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                    Text(template.name, style = MaterialTheme.typography.bodyLarge)
                                    if (template.note.isNotBlank()) {
                                        Text(
                                            template.note,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                        )
                                    }
                                }
                                Text(
                                    MoneyFormatter.centsToYuan(template.amount, showSymbol = true),
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onManage, modifier = Modifier.testTag("template_manage")) {
                    Text("管理模板")
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        modifier = Modifier.padding(start = 4.dp).size(14.dp),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
