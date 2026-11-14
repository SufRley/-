package com.xxjz.ledger.feature.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
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
import androidx.compose.ui.window.Dialog
import com.xxjz.ledger.core.common.DateUtils
import com.xxjz.ledger.core.common.MoneyFormatter
import com.xxjz.ledger.domain.model.InstallmentSchedule
import com.xxjz.ledger.domain.model.Merchant
import com.xxjz.ledger.domain.model.Tag
import com.xxjz.ledger.domain.model.TxnType
import java.time.LocalTime

/** 十二枚胶囊里，本轮落地的八枚（PRD v1.9.3 第 6.8 条按参考图定稿）。 */
private const val CHIP_TAG_DATE = "chip_date"
private const val CHIP_TAG_TIME = "chip_time"
private const val CHIP_TAG_TAGS = "chip_tags"
private const val CHIP_TAG_MERCHANT = "chip_merchant"
private const val CHIP_TAG_REIMBURSE = "chip_reimburse"
private const val CHIP_TAG_REFUND = "chip_refund"
private const val CHIP_TAG_INSTALLMENT = "chip_installment"
private const val CHIP_TAG_STATS = "chip_stats"

private val WEEK_LABELS = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

/**
 * 日期胶囊文案：「今天 周六」/「昨天 周五」/「10/12 周三」。
 *
 * 带周几是参考图的做法，也是记账时最有用的一格信息——「上周三那顿饭」比「10/12」好认太多。
 * 跨年时补上年份，否则「01/05」到底是今年还是去年分不清。
 */
internal fun dateChipText(millis: Long): String {
    val date = DateUtils.toDate(millis)
    val week = WEEK_LABELS[date.dayOfWeek.value - 1]
    val prefix =
        when (DateUtils.friendlyDate(date)) {
            "今天" -> "今天"
            "昨天" -> "昨天"
            "前天" -> "前天"
            else ->
                if (date.year == DateUtils.today().year) {
                    "${date.monthValue}/${date.dayOfMonth}"
                } else {
                    "${date.year}/${date.monthValue}/${date.dayOfMonth}"
                }
        }
    return "$prefix $week"
}

/** 时间胶囊文案：`20:02`，补零对齐，避免长短不一让胶囊抖动。 */
internal fun timeChipText(millis: Long): String {
    val time = DateUtils.toDateTime(millis).toLocalTime()
    return "%02d:%02d".format(time.hour, time.minute)
}

/**
 * 标签胶囊文案：没选是「无标签」，选一个是名字，选多个是「第一个 +N」。
 *
 * 全列出来会把胶囊撑成一行，动态宽度在多选时会让整行胶囊左右乱跳。
 */
internal fun tagChipText(
    all: List<Tag>,
    selected: Set<String>,
): String {
    if (selected.isEmpty()) return "无标签"
    val names = all.filter { it.id in selected }.map { it.name }
    if (names.isEmpty()) return "已选 ${selected.size} 个标签"
    return if (names.size == 1) names.first() else "${names.first()} +${names.size - 1}"
}

/** 分期胶囊文案：「无分期」/「分 3 期」。 */
internal fun installmentChipText(periods: Int?): String = periods?.let { "分 $it 期" } ?: "无分期"

/** 退款胶囊文案：「无退款」/「已退 ¥50.00」。 */
internal fun refundChipText(refundedCents: Long): String =
    if (refundedCents > 0L) "已退 ${MoneyFormatter.centsToYuan(refundedCents, showSymbol = true)}" else "无退款"

/**
 * 状态胶囊区（PRD v1.9.3 第 6.8 条）。
 *
 * 为什么单独一个文件：`EditorScreen.kt` 已经有 600 行，八枚胶囊连同四个弹窗塞进去
 * 会直接撞上 detekt 的 `LongMethod` 与 `LargeClass`，也让「页面骨架」和「字段编辑器」混在一起。
 *
 * 两行各自横向滑动、按内容自然排布，不强制铺满：胶囊宽度由文案决定，硬铺满会让
 * 「已退 ¥1,234.00」被压成两行。
 */
@Composable
internal fun EditorStateChips(
    state: EditorUiState,
    viewModel: EditorViewModel,
) {
    val moneyType = state.type == TxnType.EXPENSE || state.type == TxnType.INCOME
    var showDate by remember { mutableStateOf(false) }
    var showTime by remember { mutableStateOf(false) }
    var showTags by remember { mutableStateOf(false) }
    var showMerchants by remember { mutableStateOf(false) }
    var showRefund by remember { mutableStateOf(false) }
    var showInstallment by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("state_chips")) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = ChipPadding) {
            item {
                StateChip(text = dateChipText(state.dateTime), tag = CHIP_TAG_DATE) { showDate = true }
            }
            item {
                StateChip(text = timeChipText(state.dateTime), tag = CHIP_TAG_TIME) { showTime = true }
            }
            item {
                StateChip(
                    text = tagChipText(state.tags, state.selectedTagIds),
                    active = state.selectedTagIds.isNotEmpty(),
                    tag = CHIP_TAG_TAGS,
                ) { showTags = true }
            }
            if (moneyType) {
                item {
                    StateChip(
                        text = state.merchantName ?: "无商家",
                        active = state.merchantId != null,
                        tag = CHIP_TAG_MERCHANT,
                    ) { showMerchants = true }
                }
            }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = ChipPadding) {
            if (moneyType) {
                item {
                    StateChip(
                        text = if (state.pendingReimbursement) "待报销" else "非报销",
                        active = state.pendingReimbursement,
                        tag = CHIP_TAG_REIMBURSE,
                    ) { viewModel.togglePendingReimbursement() }
                }
                // 退款只对「已存过的支出」开放：还没落库的账没有 id，退不了
                if (state.refundable || state.refundedCents > 0L) {
                    item {
                        StateChip(
                            text = refundChipText(state.refundedCents),
                            active = state.refundedCents > 0L,
                            tag = CHIP_TAG_REFUND,
                        ) { showRefund = true }
                    }
                }
                if (state.installmentAvailable) {
                    item {
                        StateChip(
                            text = installmentChipText(state.installmentPeriods),
                            active = state.installmentPeriods != null,
                            tag = CHIP_TAG_INSTALLMENT,
                        ) { showInstallment = true }
                    }
                }
                item {
                    StateChip(
                        text = if (state.excludedFromStats) "不计入收支" else "计入收支和预算",
                        active = state.excludedFromStats,
                        tag = CHIP_TAG_STATS,
                    ) { viewModel.toggleExcludedFromStats() }
                }
            }
        }
    }

    if (showDate) {
        EditorDatePickerDialog(
            initialMillis = state.dateTime,
            onPick = {
                viewModel.setDateTime(keepTimeOf(state.dateTime, it))
                showDate = false
            },
            onDismiss = { showDate = false },
        )
    }
    if (showTime) {
        TimePickerDialog(
            initialMinutes = DateUtils.toDateTime(state.dateTime).toLocalTime(),
            onPick = {
                viewModel.setDateTime(withTimeOf(state.dateTime, it))
                showTime = false
            },
            onDismiss = { showTime = false },
        )
    }
    if (showTags) {
        TagPickerDialog(
            state = state,
            onToggle = viewModel::toggleTag,
            onDismiss = { showTags = false },
        )
    }
    if (showMerchants) {
        MerchantPickerDialog(
            merchants = state.merchants,
            selectedId = state.merchantId,
            onSelect = {
                viewModel.setMerchant(it)
                showMerchants = false
            },
            onCreate = {
                viewModel.createMerchant(it)
                showMerchants = false
            },
            onDismiss = { showMerchants = false },
        )
    }
    if (showRefund) {
        RefundDialog(
            refundableCents = (state.amountCents ?: 0L) - state.refundedCents,
            value = state.refundText,
            onValueChange = viewModel::setRefundText,
            onConfirm = {
                viewModel.submitRefund()
                showRefund = false
            },
            onDismiss = {
                viewModel.clearRefundText()
                showRefund = false
            },
        )
    }
    if (showInstallment) {
        InstallmentDialog(
            current = state.installmentPeriods,
            amountCents = state.effectiveAmount ?: 0L,
            onConfirm = {
                viewModel.setInstallmentPeriods(it)
                showInstallment = false
            },
            onDismiss = { showInstallment = false },
        )
    }
}

private val ChipPadding = PaddingValues(horizontal = 0.dp)

/** 一枚状态胶囊：圆角 50、高 34dp、浅灰底；激活时换主色容器。 */
@Composable
private fun StateChip(
    text: String,
    tag: String,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color =
            if (active) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
            },
        modifier = Modifier.height(34.dp).testTag(tag),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 14.dp)) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                color =
                    if (active) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                maxLines = 1,
            )
        }
    }
}

/** 只换日期、保留原来的时分：用户点了「昨天」，不该顺手把 20:02 变成 00:00。 */
internal fun keepTimeOf(
    originalMillis: Long,
    newDateMillis: Long,
): Long {
    val time = DateUtils.toDateTime(originalMillis).toLocalTime()
    return DateUtils.toMillis(DateUtils.toDate(newDateMillis), time)
}

/** 只换时分、保留原来的日期。 */
internal fun withTimeOf(
    originalMillis: Long,
    newTime: LocalTime,
): Long = DateUtils.toMillis(DateUtils.toDate(originalMillis), newTime)

/** 时间选择器：M3 的 [TimePicker] 需要自己套一层 Dialog 与确认按钮。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerDialog(
    initialMinutes: LocalTime,
    onPick: (LocalTime) -> Unit,
    onDismiss: () -> Unit,
) {
    val state =
        rememberTimePickerState(
            initialHour = initialMinutes.hour,
            initialMinute = initialMinutes.minute,
            is24Hour = true,
        )
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                TimePicker(state = state)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    TextButton(
                        onClick = { onPick(LocalTime.of(state.hour, state.minute)) },
                        modifier = Modifier.testTag("time_confirm"),
                    ) { Text("确定") }
                }
            }
        }
    }
}

/** 标签多选抽屉：点一下切换，关掉即生效（没有「确定」——每点一次都已经改到 state）。 */
@Composable
private fun TagPickerDialog(
    state: EditorUiState,
    onToggle: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth().testTag("tag_picker"),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("标签", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                if (state.tags.isEmpty()) {
                    Text(
                        "还没有标签，去「设置 → 分类标签」里先建一个。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                LazyColumn(Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(state.tags, key = { it.id }) { tag ->
                        val checked = tag.id in state.selectedTagIds
                        Surface(
                            onClick = { onToggle(tag.id) },
                            shape = RoundedCornerShape(10.dp),
                            color =
                                if (checked) {
                                    MaterialTheme.colorScheme.secondaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surface
                                },
                            modifier = Modifier.fillMaxWidth().testTag("tag_row_${tag.id}"),
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(tag.name, style = MaterialTheme.typography.bodyLarge)
                                Spacer(Modifier.weight(1f))
                                if (checked) {
                                    Text(
                                        "已选",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    )
                                }
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss, modifier = Modifier.testTag("tag_picker_done")) { Text("完成") }
                }
            }
        }
    }
}

/**
 * 商家抽屉：搜索 + 常用列表 + 现场新建。
 *
 * 「常用」按使用次数排（`suggest` 已经是这个顺序）；搜不到时给一个「新建『xxx』」，
 * 否则用户第一次输入「星巴克」只能退出页面去设置里建。
 */
@Composable
private fun MerchantPickerDialog(
    merchants: List<Merchant>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var keyword by remember { mutableStateOf("") }
    val filtered =
        if (keyword.isBlank()) {
            merchants
        } else {
            merchants.filter { it.name.contains(keyword.trim(), ignoreCase = true) }
        }
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth().testTag("merchant_picker"),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("商家", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = keyword,
                    onValueChange = { keyword = it.take(20) },
                    placeholder = { Text("搜索或输入新商家") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().testTag("merchant_search"),
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.heightIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(filtered, key = { it.id }) { merchant ->
                        val checked = merchant.id == selectedId
                        Surface(
                            onClick = { onSelect(merchant.id) },
                            shape = RoundedCornerShape(10.dp),
                            color =
                                if (checked) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surface
                                },
                            modifier = Modifier.fillMaxWidth().testTag("merchant_row_${merchant.id}"),
                        ) {
                            Text(
                                merchant.name,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 12.dp),
                            )
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    if (keyword.isNotBlank() && filtered.none { it.name == keyword.trim() }) {
                        TextButton(
                            onClick = { onCreate(keyword) },
                            modifier = Modifier.testTag("merchant_create"),
                        ) { Text("＋ 新建「${keyword.trim()}」") }
                    } else {
                        Spacer(Modifier.size(1.dp))
                    }
                    TextButton(onClick = onDismiss) { Text("取消") }
                }
            }
        }
    }
}

/** 退款弹窗：只填金额，日期与分类都由原支出决定（退款是一笔独立的收入流水）。 */
@Composable
private fun RefundDialog(
    refundableCents: Long,
    value: String,
    onValueChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("记一笔退款") },
        text = {
            Column {
                Text(
                    "最多可退 ${MoneyFormatter.centsToYuan(refundableCents, showSymbol = true)}。退款会单独生成一笔收入，原支出保持不变。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    placeholder = { Text("0.00") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().testTag("refund_input"),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag("refund_confirm")) { Text("退款") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/**
 * 分期弹窗：只填期数。
 *
 * 手续费本轮不做输入（PRD 没要求），按 0 处理；期数区间由 [InstallmentSchedule] 定，
 * 越界直接不让确定，而不是存进去再报错。
 */
@Composable
private fun InstallmentDialog(
    current: Int?,
    amountCents: Long,
    onConfirm: (Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(current?.toString() ?: "") }
    val periods = text.toIntOrNull()
    val valid = periods != null && InstallmentSchedule.isValidPeriods(periods)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("分期") },
        text = {
            Column {
                Text(
                    "本金 ${MoneyFormatter.centsToYuan(amountCents, showSymbol = true)}，" +
                        "分 ${InstallmentSchedule.MIN_PERIODS}~${InstallmentSchedule.MAX_PERIODS} 期。" +
                        "每期金额保存后在账户详情里看到。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { raw -> text = raw.filter { it.isDigit() }.take(2) },
                    placeholder = { Text("期数") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().testTag("installment_input"),
                )
                if (periods != null && !valid) {
                    Text(
                        "${InstallmentSchedule.MIN_PERIODS}~${InstallmentSchedule.MAX_PERIODS} 期之间才能分期",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(periods) },
                enabled = valid,
                modifier = Modifier.testTag("installment_confirm"),
            ) { Text("确定") }
        },
        dismissButton = {
            TextButton(
                onClick = { onConfirm(null) },
                modifier = Modifier.testTag("installment_clear"),
            ) { Text("不分期") }
        },
    )
}
