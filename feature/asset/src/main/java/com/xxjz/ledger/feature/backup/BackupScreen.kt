package com.xxjz.ledger.feature.backup

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xxjz.ledger.core.common.DateUtils
import java.io.File

/** P23 备份与恢复入口。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(
    onBack: () -> Unit = {},
    viewModel: BackupViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var restoreExpanded by remember { mutableStateOf(false) }

    val saveLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            uri?.let { viewModel.exportTo(it.toString()) }
        }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("备份与恢复") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .testTag("screen_backup"),
        ) {
            Spacer(Modifier.height(8.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(0.dp))
                        Text(
                            "  端到端加密",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "备份文件用你设置的密码加密（AES-256），密码只存在你脑子里。" +
                            "忘记密码就无法恢复，请务必记牢。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            Text("导出备份", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                if (state.lastBackupAt > 0) {
                    "上次备份：${DateUtils.formatDateTime(state.lastBackupAt)}"
                } else {
                    "还没有备份过，建议定期导出一次"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = state.password,
                onValueChange = viewModel::setPassword,
                label = { Text("设置备份密码（至少 $MIN_PASSWORD_LENGTH 位）") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                isError = state.passwordError != null,
                modifier = Modifier.fillMaxWidth().testTag("backup_password"),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = state.confirmPassword,
                onValueChange = viewModel::setConfirmPassword,
                label = { Text("再次输入密码") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                isError = state.passwordError != null,
                supportingText = state.passwordError?.let { { Text(it) } },
                modifier = Modifier.fillMaxWidth().testTag("backup_password_confirm"),
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    if (viewModel.validate()) {
                        saveLauncher.launch(defaultBackupName())
                    }
                },
                enabled = !state.exporting,
                modifier = Modifier.fillMaxWidth().testTag("btn_export"),
            ) {
                if (state.exporting) {
                    CircularProgressIndicator(Modifier.height(18.dp), strokeWidth = 2.dp)
                } else {
                    Text("选择位置并导出")
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("每周提醒我备份", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "超过 7 天没备份时在首页提示",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = state.reminderEnabled,
                    onCheckedChange = viewModel::setReminderEnabled,
                    modifier = Modifier.testTag("switch_backup_reminder"),
                )
            }

            Spacer(Modifier.height(24.dp))
            Text("从备份恢复", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                "恢复会覆盖当前全部数据，建议先导出一份当前备份",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { restoreExpanded = true },
                modifier = Modifier.fillMaxWidth().testTag("btn_restore"),
            ) { Text("选择备份文件") }

            Spacer(Modifier.height(32.dp))
        }
    }

    state.exported?.let { file ->
        AlertDialog(
            onDismissRequest = viewModel::clearExported,
            title = { Text("导出成功") },
            text = {
                Column {
                    Text(file.name, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(6.dp))
                    Text("大小：${file.sizeLabel}")
                    Text("时间：${file.timeLabel}")
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "请记住密码：${file.password}。忘记密码将无法恢复这份备份。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        runCatching { context.shareBackup(file) }
                    },
                ) {
                    Icon(Icons.Outlined.Share, contentDescription = null)
                    Text("  分享")
                }
            },
            dismissButton = { TextButton(onClick = viewModel::clearExported) { Text("完成") } },
        )
    }

    if (restoreExpanded) {
        RestoreSheet(onDismiss = { restoreExpanded = false })
    }
}

/** 恢复流程放在弹层里，避免误触后一路走到底。 */
@Composable
private fun RestoreSheet(onDismiss: () -> Unit) {
    RestoreDialog(onDismiss = onDismiss)
}

@Composable
private fun RestoreDialog(
    onDismiss: () -> Unit,
    viewModel: RestoreViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirmOverwrite by remember { mutableStateOf(false) }

    val pickLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { viewModel.selectFile(it.toString()) }
        }

    if (state.done) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("恢复完成") },
            text = { Text("数据已按备份内容覆盖，返回首页即可看到。") },
            confirmButton = { TextButton(onClick = onDismiss) { Text("知道了") } },
        )
        return
    }

    val preview = state.preview
    if (preview == null) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("从备份恢复") },
            text = {
                Column {
                    OutlinedButton(
                        onClick = { pickLauncher.launch(arrayOf("*/*")) },
                        modifier = Modifier.fillMaxWidth().testTag("btn_pick_backup"),
                    ) { Text(if (state.fileName.isBlank()) "选择 .xxjzbak 文件" else state.fileName) }

                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = state.password,
                        onValueChange = viewModel::setPassword,
                        label = { Text("备份密码") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        isError = state.error != null,
                        supportingText = state.error?.let { { Text(it) } },
                        modifier = Modifier.fillMaxWidth().testTag("restore_password"),
                    )
                    if (state.decrypting) {
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.height(16.dp), strokeWidth = 2.dp)
                            Text("  正在解密…", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = viewModel::decryptPreview,
                    enabled = !state.decrypting && state.uriString.isNotBlank(),
                ) { Text("解密并预览") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        )
        return
    }

    val stats = preview.stats
    AlertDialog(
        onDismissRequest = { if (!state.decrypting) viewModel.backToPassword() },
        title = { Text("确认覆盖导入？") },
        text = {
            Column {
                Text("备份时间：${DateUtils.formatDateTime(stats.createdAt)}")
                Text("账目：${stats.txnCount} 笔")
                Text("账户：${stats.accountCount} 个")
                Text("分类：${stats.categoryCount} 个")
                Spacer(Modifier.height(10.dp))
                Text(
                    "导入会用备份内容替换当前全部数据，无法撤销。建议先导出一份当前备份。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                if (state.error != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(state.error!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { confirmOverwrite = true }, enabled = !state.decrypting) {
                Text(if (state.decrypting) "正在导入…" else "覆盖导入")
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { viewModel.backToPassword() }) { Text("换个密码") }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )

    if (confirmOverwrite) {
        AlertDialog(
            onDismissRequest = { confirmOverwrite = false },
            title = { Text("最后确认") },
            text = { Text("当前所有账目、账户、预算都会被删除，替换成备份里的内容。确定继续吗？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmOverwrite = false
                        viewModel.confirmRestore()
                    },
                ) { Text("确定覆盖") }
            },
            dismissButton = { TextButton(onClick = { confirmOverwrite = false }) { Text("再想想") } },
        )
    }
}

/** 备份文件名。 */
internal fun defaultBackupName(now: Long = System.currentTimeMillis()): String {
    val t = DateUtils.today()
    return "一心记账备份_%04d%02d%02d.xxjzbak".format(t.year, t.monthValue, t.dayOfMonth)
}

/**
 * 通过 FileProvider 把备份文件分享出去。
 *
 * 分享前把 `content://` 内容复制到缓存目录：分享给别的 App 时目标应用未必有原目录的读权限，
 * 复制一份到自己的 cache 再由 FileProvider 授权最稳。
 */
private fun Context.shareBackup(file: ExportedFile) {
    val cacheFile = File(cacheDir, "share_backup.xxjzbak")
    contentResolver.openInputStream(android.net.Uri.parse(file.uriString))?.use { input ->
        cacheFile.outputStream().use { output -> input.copyTo(output) }
    }
    val uri =
        FileProvider.getUriForFile(
            this,
            "$packageName.fileprovider",
            cacheFile,
        )
    val intent =
        Intent(Intent.ACTION_SEND).apply {
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    startActivity(Intent.createChooser(intent, "分享备份文件"))
}
