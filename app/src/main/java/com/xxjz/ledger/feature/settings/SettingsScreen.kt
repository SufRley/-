package com.xxjz.ledger.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xxjz.ledger.core.common.PinHasher
import com.xxjz.ledger.data.datastore.DefaultTimeMode
import com.xxjz.ledger.data.datastore.KeypadType
import com.xxjz.ledger.ui.lock.BiometricAvailability

/** 设置页（AC-95）：记账默认值、外观、提醒、回收站、应用锁。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var pinDialog by remember { mutableStateOf(PinDialogMode.HIDDEN) }
    // 设备是否已录入指纹/面容：没录入时开关置灰，避免「打开了却永远弹不出来」
    val context = LocalContext.current
    val biometricReady = remember(context) { BiometricAvailability.hasEnrolledBiometric(context) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("settings_back")) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("screen_settings")) {
            item { SectionTitle("记账默认值") }
            item {
                RowSetting("默认账户", state.defaultAccountName) {
                    // 简化交互：循环切换「未设置 → 各账户」，自用场景够用且少一层弹窗
                    val ids = listOf<String?>(null) + state.accounts.map { it.id }
                    val next = ids[(ids.indexOf(state.prefs.defaultAccountId) + 1).coerceAtMost(ids.lastIndex)]
                    viewModel.setDefaultAccount(next)
                }
            }
            item {
                PickerSetting("默认时间") {
                    TimeModeChip("当前时间", state.prefs.defaultTimeMode == DefaultTimeMode.NOW) {
                        viewModel.setDefaultTimeMode(DefaultTimeMode.NOW)
                    }
                    TimeModeChip("固定时间", state.prefs.defaultTimeMode == DefaultTimeMode.FIXED) {
                        viewModel.setDefaultTimeMode(DefaultTimeMode.FIXED)
                    }
                    TimeModeChip("上次时间", state.prefs.defaultTimeMode == DefaultTimeMode.LAST) {
                        viewModel.setDefaultTimeMode(DefaultTimeMode.LAST)
                    }
                }
            }
            item {
                PickerSetting("小数位") {
                    for (places in 0..2) {
                        FilterChip(
                            selected = state.prefs.decimalPlaces == places,
                            onClick = { viewModel.setDecimalPlaces(places) },
                            label = { Text("$places 位") },
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    }
                }
            }
            item {
                PickerSetting("键盘") {
                    FilterChip(
                        selected = state.prefs.keypadType == KeypadType.CALC,
                        onClick = { viewModel.setKeypadType(KeypadType.CALC) },
                        label = { Text("计算器") },
                        modifier = Modifier.padding(end = 8.dp),
                    )
                    FilterChip(
                        selected = state.prefs.keypadType == KeypadType.NUMBER,
                        onClick = { viewModel.setKeypadType(KeypadType.NUMBER) },
                        label = { Text("大按钮") },
                    )
                }
            }

            item { SectionTitle("外观") }
            item {
                PickerSetting("主题") {
                    listOf("SYSTEM" to "跟随系统", "LIGHT" to "浅色", "DARK" to "深色").forEach { (mode, label) ->
                        FilterChip(
                            selected = state.prefs.themeMode == mode,
                            onClick = { viewModel.setThemeMode(mode) },
                            label = { Text(label) },
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    }
                }
            }
            item {
                SwitchSetting("收支配色反转", state.prefs.amountColorsReversed, "switch_colors_reversed") {
                    viewModel.setAmountColorsReversed(it)
                }
            }

            item { SectionTitle("提醒") }
            item {
                SwitchSetting("每日记账提醒", state.prefs.dailyReminderEnabled, "switch_daily_reminder") {
                    viewModel.setDailyReminder(it)
                }
            }
            item {
                RowSetting("提醒时间", state.reminderTimeLabel) {
                    // 每点一次往后挪一小时，跨 24 点回绕；自用场景不需要精确到分钟的选择器
                    val next = (state.prefs.dailyReminderMinute + 60) % (24 * 60)
                    viewModel.setReminderTime(next / 60, next % 60)
                }
            }
            item {
                SwitchSetting("每周备份提醒", state.prefs.backupReminderEnabled, "switch_backup_reminder") {
                    viewModel.setBackupReminder(it)
                }
            }

            item { SectionTitle("数据") }
            item {
                RowSetting("回收站保留", state.recycleBinLabel) {
                    val options = listOf(7, 15, 30, 90, 0)
                    val next = options[(options.indexOf(state.prefs.recycleBinDays) + 1).mod(options.size)]
                    viewModel.setRecycleBinDays(next)
                }
            }

            item { SectionTitle("安全") }
            item {
                SwitchSetting("应用锁", state.prefs.appLockEnabled, "switch_app_lock") { on ->
                    if (on) pinDialog = PinDialogMode.CREATE else viewModel.disableAppLock()
                }
            }
            if (state.prefs.appLockEnabled) {
                item {
                    // 指纹开关（v1.8.1 #7）：以前设置里只有「改密码」，锁屏那个「改用指纹」
                    // 按钮等于藏起来了。开启后锁定时会自动拉起系统指纹弹窗。
                    SwitchSetting(
                        title = "指纹 / 面容解锁",
                        checked = state.prefs.biometricEnabled,
                        tag = "switch_biometric",
                        subtitle = if (biometricReady) "锁定后自动弹出指纹，失败可改用密码" else "未检测到已录入的指纹或面容",
                        enabled = biometricReady,
                    ) { on -> viewModel.setBiometricEnabled(on) }
                }
                item {
                    RowSetting("修改密码", "") { pinDialog = PinDialogMode.CHANGE }
                }
            }
            item { Spacer(Modifier.size(32.dp)) }
        }
    }

    state.message?.let {
        AlertDialog(
            onDismissRequest = viewModel::consumeMessage,
            confirmButton = { TextButton(onClick = viewModel::consumeMessage) { Text("好") } },
            text = { Text(it, modifier = Modifier.testTag("settings_message")) },
        )
    }

    when (pinDialog) {
        PinDialogMode.CREATE ->
            PinDialog(
                title = "设置应用锁密码",
                fields = listOf("密码（4~8 位数字）", "再输一次"),
                onDismiss = { pinDialog = PinDialogMode.HIDDEN },
            ) { values ->
                if (values[0] != values[1]) {
                    viewModel.setPinMismatch()
                } else {
                    viewModel.enableAppLock(values[0])
                    pinDialog = PinDialogMode.HIDDEN
                }
            }
        PinDialogMode.CHANGE ->
            PinDialog(
                title = "修改密码",
                fields = listOf("原密码", "新密码（4~8 位数字）"),
                onDismiss = { pinDialog = PinDialogMode.HIDDEN },
            ) { values ->
                viewModel.changePin(values[0], values[1])
                pinDialog = PinDialogMode.HIDDEN
            }
        PinDialogMode.HIDDEN -> Unit
    }
}

private enum class PinDialogMode { HIDDEN, CREATE, CHANGE }

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 4.dp),
    )
}

@Composable
private fun RowSetting(
    title: String,
    value: String,
    onClick: () -> Unit,
) {
    Column {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.weight(1f))
            Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun PickerSetting(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Row(Modifier.padding(top = 8.dp)) { content() }
    }
}

@Composable
private fun TimeModeChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(text) },
        modifier = Modifier.padding(end = 8.dp),
    )
}

@Composable
private fun SwitchSetting(
    title: String,
    checked: Boolean,
    tag: String,
    subtitle: String? = null,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color =
                    if (enabled) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled, modifier = Modifier.testTag(tag))
    }
}

@Composable
private fun PinDialog(
    title: String,
    fields: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (List<String>) -> Unit,
) {
    val values = remember(fields) { fields.map { "" }.toMutableStateList() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                fields.forEachIndexed { index, label ->
                    OutlinedTextField(
                        value = values[index],
                        onValueChange = { input ->
                            if (input.length <= PinHasher.MAX_LENGTH && input.all(Char::isDigit)) values[index] = input
                        },
                        label = { Text(label) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier.fillMaxWidth().testTag("pin_field_$index"),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(values.toList()) },
                enabled = values.all { PinHasher.isValidFormat(it) },
            ) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
