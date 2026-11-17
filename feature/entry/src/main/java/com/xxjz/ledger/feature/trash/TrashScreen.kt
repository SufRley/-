package com.xxjz.ledger.feature.trash

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
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xxjz.ledger.core.designsystem.component.EmptyState

/**
 * P22 回收站（AC-34、AC-50、AC-76）。
 *
 * 三个分页各自独立还原/彻底删除；顶部显示保留策略，避免用户以为「删了就没了」或「永远都在」。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(
    onBack: () -> Unit = {},
    viewModel: TrashViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var confirmClear by remember { mutableStateOf(false) }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("回收站") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(onClick = { confirmClear = true }, enabled = state.current.isNotEmpty()) {
                        Text("清空本页")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).testTag("screen_trash")) {
            TabRow(selectedTabIndex = state.tab.ordinal) {
                TrashTab.entries.forEach { tab ->
                    val count =
                        when (tab) {
                            TrashTab.TXN -> state.txns.size
                            TrashTab.ACCOUNT -> state.accounts.size
                            TrashTab.BUDGET -> state.budgets.size
                        }
                    Tab(
                        selected = state.tab == tab,
                        onClick = { viewModel.selectTab(tab) },
                        text = { Text(if (count > 0) "${tab.label} ($count)" else tab.label) },
                        modifier = Modifier.testTag("trash_tab_${tab.name}"),
                    )
                }
            }
            Text(
                text = state.retentionLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            )

            if (state.current.isEmpty()) {
                EmptyState(
                    title = "回收站是空的",
                    description = "删除的${state.tab.label}会先放到这里，可以随时还原",
                    modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
                )
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(state.current, key = { "${it.id}-${it.monthKey}" }) { item ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(item.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    item.subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(
                                onClick = {
                                    when (state.tab) {
                                        TrashTab.TXN -> viewModel.restoreTxn(item.id)
                                        TrashTab.ACCOUNT -> viewModel.restoreAccount(item.id)
                                        TrashTab.BUDGET -> viewModel.restoreBudget(item.id, item.monthKey)
                                    }
                                },
                                modifier = Modifier.testTag("restore_${item.id}"),
                            ) {
                                Icon(Icons.Outlined.Restore, contentDescription = "还原")
                            }
                            IconButton(
                                onClick = {
                                    when (state.tab) {
                                        TrashTab.TXN -> viewModel.purgeTxn(item.id)
                                        TrashTab.ACCOUNT -> viewModel.purgeAccount(item.id)
                                        TrashTab.BUDGET -> viewModel.purgeBudget(item.id, item.monthKey)
                                    }
                                },
                                modifier = Modifier.testTag("purge_${item.id}"),
                            ) {
                                Icon(
                                    Icons.Outlined.DeleteForever,
                                    contentDescription = "彻底删除",
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空本页？") },
            text = { Text("这 ${state.current.size} 条${state.tab.label}会被彻底删除，无法恢复。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearCurrent()
                        confirmClear = false
                    },
                ) { Text("彻底删除") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } },
        )
    }
}
