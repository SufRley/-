package com.xxjz.ledger.feature.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DatePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.xxjz.ledger.core.common.DateUtils
import com.xxjz.ledger.core.common.MoneyFormatter
import com.xxjz.ledger.domain.model.Account
import com.xxjz.ledger.domain.model.Category
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * 分类选择（AC-05）：左侧大类、右侧小类网格，选中即回填。
 * 用 Dialog 而不是嵌套 BottomSheet，避免两层弹层手势冲突。
 */
@Composable
fun CategoryPickerDialog(
    tree: List<Pair<Category, List<Category>>>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    if (tree.isEmpty()) return
    val initialMajor =
        tree.firstOrNull { (major, children) -> major.id == selectedId || children.any { it.id == selectedId } }
            ?: tree.first()
    var currentMajor by remember { mutableStateOf(initialMajor) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp).testTag("category_picker"),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("选择分类", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth().height(360.dp)) {
                    LazyColumn(
                        modifier = Modifier.width(104.dp).fillMaxHeight(),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(tree, key = { it.first.id }) { pair ->
                            val major = pair.first
                            val selected = major.id == currentMajor.first.id
                            Surface(
                                onClick = { currentMajor = pair },
                                shape = RoundedCornerShape(10.dp),
                                color =
                                    if (selected) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surface
                                    },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    text = major.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(vertical = 10.dp, horizontal = 8.dp),
                                )
                            }
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    val children = currentMajor.second
                    if (children.isEmpty()) {
                        Box(Modifier.weight(1f).heightIn(min = 120.dp), contentAlignment = Alignment.Center) {
                            TextButton(onClick = { onSelect(currentMajor.first.id) }) {
                                Text("选择「${currentMajor.first.name}」")
                            }
                        }
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(3),
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            items(children, key = { it.id }) { child ->
                                val selected = child.id == selectedId
                                Surface(
                                    onClick = { onSelect(child.id) },
                                    shape = RoundedCornerShape(10.dp),
                                    color =
                                        if (selected) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.surfaceVariant
                                        },
                                ) {
                                    Text(
                                        text = child.name,
                                        textAlign = TextAlign.Center,
                                        style = MaterialTheme.typography.bodySmall,
                                        color =
                                            if (selected) {
                                                MaterialTheme.colorScheme.onPrimary
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            },
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                }
            }
        }
    }
}

/** 账户选择（AC-07）：含实时余额。 */
@Composable
fun AccountPickerDialog(
    accounts: List<Account>,
    selectedId: String?,
    title: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth().testTag("account_picker"),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Column(Modifier.heightIn(max = 380.dp)) {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(accounts, key = { it.id }) { account ->
                            val selected = account.id == selectedId
                            Surface(
                                onClick = { onSelect(account.id) },
                                shape = RoundedCornerShape(10.dp),
                                color =
                                    if (selected) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surface
                                    },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(account.name, style = MaterialTheme.typography.bodyLarge)
                                    Spacer(Modifier.weight(1f))
                                    Text(
                                        "¥${MoneyFormatter.centsToYuan(account.balance)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                }
            }
        }
    }
}

/** 日期选择（AC-06）：日历 + 今天/昨天/前天快捷。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorDatePickerDialog(
    initialMillis: Long,
    onPick: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val initialDate = DateUtils.toDate(initialMillis)
    val state =
        rememberDatePickerState(
            initialSelectedDateMillis = initialDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 8.dp)) {
                    QuickDate("今天", 0, onPick)
                    QuickDate("昨天", 1, onPick)
                    QuickDate("前天", 2, onPick)
                }
                DatePicker(state = state)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    TextButton(
                        onClick = {
                            val millis = state.selectedDateMillis
                            if (millis != null) {
                                val date = java.time.Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                                onPick(DateUtils.toMillis(date))
                            } else {
                                onDismiss()
                            }
                        },
                        modifier = Modifier.testTag("date_confirm"),
                    ) { Text("确定") }
                }
            }
        }
    }
}

@Composable
private fun QuickDate(
    label: String,
    daysAgo: Long,
    onPick: (Long) -> Unit,
) {
    val date: LocalDate = DateUtils.today().minusDays(daysAgo)
    Surface(
        onClick = { onPick(DateUtils.toMillis(date)) },
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}
