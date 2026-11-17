package com.xxjz.ledger.feature.report

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xxjz.ledger.core.common.DateUtils
import com.xxjz.ledger.core.common.MoneyFormatter
import com.xxjz.ledger.core.designsystem.component.CompactHeader
import com.xxjz.ledger.core.designsystem.component.EmptyState
import com.xxjz.ledger.core.ui.chart.ChartBox
import com.xxjz.ledger.core.ui.chart.ChartPoint
import com.xxjz.ledger.core.ui.chart.EmptyRing
import com.xxjz.ledger.core.ui.chart.LabeledPieChart
import com.xxjz.ledger.core.ui.chart.LineChart
import com.xxjz.ledger.core.ui.chart.PieCallout
import com.xxjz.ledger.core.ui.chart.PieSlice
import com.xxjz.ledger.core.ui.chart.toArgbLong
import com.xxjz.ledger.domain.usecase.AssetChange
import com.xxjz.ledger.domain.usecase.AssetChangeKind
import com.xxjz.ledger.domain.usecase.AssetSlice
import com.xxjz.ledger.domain.usecase.BreakdownCalculator
import com.xxjz.ledger.domain.usecase.TrendPeriod
import com.xxjz.ledger.domain.usecase.TrendPoint
import kotlin.math.abs
import com.xxjz.ledger.domain.usecase.BreakdownCalculator.Mode as BreakdownMode

/**
 * 资产报表页（v1.8.0 / PRD 5.2）。
 *
 * 布局按 D12 决策：**纵向滚动，四个板块各约半屏**，用四种描边色区分
 * （资产绿 / 负债红 / 趋势蓝 / 变化金），两屏左右看完。
 *
 * @param onOpenTxn 点击变化记录跳流水详情；元事件（改市值/改初始余额）没有流水，不可点击
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssetReportScreen(
    onBack: () -> Unit,
    onOpenTxn: (String) -> Unit = {},
    onOpenSettings: () -> Unit = {},
    viewModel: AssetReportViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // 每块至少半屏高：小屏上一屏放两块，大屏上留白而不是把内容拉伸变形
    val halfScreen = (LocalConfiguration.current.screenHeightDp / 2).dp
    // 两块卡片各自记住「按账户 / 按类型」（v1.9.3 第 5 条：截图里两块就不一致，说明本该独立）
    var assetsMode by remember { mutableStateOf(BreakdownMode.ACCOUNT) }
    var liabilityMode by remember { mutableStateOf(BreakdownMode.ACCOUNT) }

    // v1.9.3 第 4 条：去掉 TopAppBar（64dp + 状态栏 inset），换紧凑头，
    // 与明细 / 账户 / 待报销页同一起点，不再出现「上面空一块」。
    Column(Modifier.fillMaxSize().testTag("screen_asset_report")) {
        CompactHeader(
            title = "资产报表",
            leading = {
                IconButton(onClick = onBack, modifier = Modifier.testTag("asset_report_back")) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                }
            },
            actions = {
                IconButton(onClick = onOpenSettings, modifier = Modifier.testTag("asset_report_settings")) {
                    Icon(Icons.Outlined.Settings, contentDescription = "设置")
                }
            },
        )
        if (state.loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Column
        }
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            AssetPanel(state = state, mode = assetsMode, onModeChange = { assetsMode = it })
            LiabilityPanel(state = state, mode = liabilityMode, onModeChange = { liabilityMode = it })
            TrendPanel(state, halfScreen, onPeriod = viewModel::setPeriod)
            ChangesPanel(state, halfScreen, onOpenTxn)
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** 板块主题色（D12：绿 / 红 / 蓝），用于数值着色与趋势线。 */
private val AssetGreen = Color(0xFF2E9E6B)
private val LiabilityRed = Color(0xFFD9534F)
private val TrendBlue = Color(0xFF3B7DD8)

/**
 * 板块容器：浅灰底、无描边、圆角 20（v1.9.3 第 5 条按截图重做）。
 *
 * 以前是白底 + 1.5dp 彩色描边 + 标题前一个彩色圆点，整页看下来像四张警告卡片。
 * 截图里是干净的浅灰卡，层级全靠留白和字号，这里就照这个来。
 *
 * @param minHeight 趋势/变化两块仍要占半屏，构成卡则由内容自然撑开
 * @param tag 测试与 UI 走查用的稳定标识
 */
@Composable
private fun SectionPanel(
    title: String,
    minHeight: Dp?,
    tag: String,
    headerExtra: @Composable (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val base = Modifier.fillMaxWidth().testTag(tag)
    Surface(
        modifier = if (minHeight != null) base.heightIn(min = minHeight) else base,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f))
                headerExtra?.invoke()
            }
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

/** 板块①：资产构成。 */
@Composable
private fun AssetPanel(
    state: AssetReportUiState,
    mode: BreakdownMode,
    onModeChange: (BreakdownMode) -> Unit,
) {
    val breakdown = remember(state.accounts, mode) { BreakdownCalculator.assets(state.accounts, mode) }
    BreakdownPanel(
        title = "资产构成",
        tag = "panel_assets",
        totalLabel = "总资产",
        mode = mode,
        onModeChange = onModeChange,
        slices = breakdown.nonZero,
        excludedInvest = breakdown.excludedInvest,
        emptyTitle = "还没有资产",
        emptyDescription = "新建账户并记上几笔账，这里就会显示构成占比",
    )
}

/** 板块②：负债构成。 */
@Composable
private fun LiabilityPanel(
    state: AssetReportUiState,
    mode: BreakdownMode,
    onModeChange: (BreakdownMode) -> Unit,
) {
    val breakdown = remember(state.accounts, mode) { BreakdownCalculator.liabilities(state.accounts, mode) }
    BreakdownPanel(
        title = "负债构成",
        tag = "panel_liability",
        totalLabel = "总负债",
        mode = mode,
        onModeChange = onModeChange,
        slices = breakdown.nonZero,
        excludedInvest = 0,
        emptyTitle = "没有负债",
        emptyDescription = "信用卡欠款、借贷未结清、储蓄卡透支都会汇总到这里",
    )
}

/**
 * 资产 / 负债构成卡（v1.9.3 第 5 条）。
 *
 * 两块共用同一套实现，但各自持有「金额 / 占比」与「账户 / 类型」两个选择——
 * 截图里资产块显示金额、负债块显示占比，这正说明它们本来就该独立。
 */
@Composable
private fun BreakdownPanel(
    title: String,
    tag: String,
    totalLabel: String,
    mode: BreakdownMode,
    onModeChange: (BreakdownMode) -> Unit,
    slices: List<AssetSlice>,
    excludedInvest: Long,
    emptyTitle: String,
    emptyDescription: String,
) {
    SectionPanel(title = title, minHeight = null, tag = tag) {
        if (slices.isEmpty()) {
            Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                EmptyState(title = emptyTitle, description = emptyDescription)
            }
        } else {
            BreakdownPie(
                slices = slices,
                total = slices.sumOf { it.amount },
                totalLabel = totalLabel,
                tag = tag,
                mode = mode,
                onModeChange = onModeChange,
            )
            if (excludedInvest > 0) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "另有投资市值 ${MoneyFormatter.centsToYuan(excludedInvest, showSymbol = true)} 未计入净资产",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("invest_excluded_hint"),
                )
            }
        }
    }
}

/** 圆心的显示模式（v1.9.0 第 4 条；v1.9.3 把圆心里的数字拿掉了）。 */
private enum class PieCenter { AMOUNT, PERCENT }

/**
 * 8 色调色板（PRD 5.2）。
 *
 * 原来 3 色在「按账户拆段」之后不够用，会撞色；扩到 8 色刚好覆盖 `MAX_SLICES`。
 * 顺序刻意让相邻色相差得开，避免两段相邻同色系。
 */
private val AssetPalette =
    listOf(
        AssetGreen,
        Color(0xFF4FA3D1),
        Color(0xFFE0A33F),
        Color(0xFF7B68B5),
        Color(0xFF3FB0A0),
        Color(0xFFC96B9B),
        Color(0xFF8AA83F),
        Color(0xFFB0774A),
    )

/** 「其他」段固定中性灰：它是聚合项，用彩色会让人误以为是真实账户。 */
private val OtherGray = Color(0xFF9E9E9E)

private fun sliceColor(
    label: String,
    index: Int,
): Color = if (label == BreakdownCalculator.OTHER_LABEL) OtherGray else AssetPalette[index % AssetPalette.size]

/**
 * 饼图 + 引导标签 + 下方总额 + 口径切换（v1.9.3 第 5 条）。
 *
 * 三个交互分开：
 * - 点扇区 → 选中该段（圆心显示它的名字和数值），再点一次取消；
 * - 点圆心 → 未选中时切换「金额 / 占比」，已选中时取消选中；
 * - 点其他地方 → 什么都不做。
 */
@Composable
private fun BreakdownPie(
    slices: List<AssetSlice>,
    total: Long,
    totalLabel: String,
    tag: String,
    mode: BreakdownMode,
    onModeChange: (BreakdownMode) -> Unit,
) {
    var selected by remember(slices) { mutableIntStateOf(-1) }
    var display by remember { mutableStateOf(PieCenter.AMOUNT) }
    val palette = slices.mapIndexed { index, slice -> sliceColor(slice.label, index) }
    val pieSlices =
        slices.mapIndexed { index, slice ->
            PieSlice(
                label = slice.label,
                value = slice.amount,
                color = palette[index].toArgbLong(),
            )
        }
    val active = slices.getOrNull(selected)

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().testTag(tag), contentAlignment = Alignment.Center) {
            if (pieSlices.isEmpty()) {
                EmptyRing(modifier = Modifier.size(180.dp))
            } else {
                LabeledPieChart(
                    slices = pieSlices,
                    // 标签跟随当前模式：金额模式写金额，占比模式写百分比（截图就是这样）
                    callouts =
                        slices.map { slice ->
                            PieCallout(
                                title = slice.label,
                                value =
                                    when (display) {
                                        PieCenter.AMOUNT -> MoneyFormatter.centsToYuan(slice.amount)
                                        PieCenter.PERCENT -> "${slice.percent(total)}%"
                                    },
                            )
                        },
                    selected = selected,
                    onSelect = { selected = if (it == selected) -1 else it },
                ) {
                    PieCenterLabel(
                        active = active,
                        display = display,
                        total = total,
                        tag = tag,
                        onClick = {
                            if (active != null) {
                                selected = -1
                            } else {
                                display = if (display == PieCenter.AMOUNT) PieCenter.PERCENT else PieCenter.AMOUNT
                            }
                        },
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        // 总额放在饼图正下方居中：以前挤在页面顶部的小字里，和饼图对不上
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = totalLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = MoneyFormatter.centsToYuan(total),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.testTag("${tag}_total"),
            )
        }
        Spacer(Modifier.height(14.dp))
        BreakdownModeSwitch(mode = mode, onChange = onModeChange, tag = tag)
    }
}

/**
 * 圆心：只放一个模式标签和双向箭头（v1.9.3 第 5 条明确要求「中间不应该有数字」）。
 *
 * 选中扇区时才临时显示「名字 + 数值」，再点一下回到模式标签——这样既不破坏截图里的
 * 干净感，又保留了「这一段到底多少」的能力。
 */
@Composable
private fun PieCenterLabel(
    active: AssetSlice?,
    display: PieCenter,
    total: Long,
    tag: String,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier =
            Modifier
                .clip(CircleShape)
                .clickable(onClick = onClick)
                .padding(6.dp)
                .testTag("${tag}_center"),
    ) {
        if (active == null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (display == PieCenter.AMOUNT) "金额" else "占比",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.Filled.SwapHoriz,
                    contentDescription = "切换金额与占比",
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
        } else {
            Text(
                text = active.label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.widthIn(max = 92.dp).testTag("${tag}_center_title"),
            )
            Text(
                text =
                    when (display) {
                        PieCenter.AMOUNT -> MoneyFormatter.centsToYuan(active.amount)
                        PieCenter.PERCENT -> "${active.percent(total)}%"
                    },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                modifier = Modifier.widthIn(max = 92.dp).testTag("${tag}_center_value"),
            )
        }
    }
}

/** 卡片底部的「账户 / 类型」分段控件（v1.9.3 第 5 条）。 */
@Composable
private fun BreakdownModeSwitch(
    mode: BreakdownMode,
    onChange: (BreakdownMode) -> Unit,
    tag: String,
) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f),
        modifier = Modifier.testTag("${tag}_mode_switch"),
    ) {
        Row(Modifier.padding(3.dp)) {
            ModeChip("账户", mode == BreakdownMode.ACCOUNT, "${tag}_mode_account") { onChange(BreakdownMode.ACCOUNT) }
            ModeChip("类型", mode == BreakdownMode.TYPE, "${tag}_mode_type") { onChange(BreakdownMode.TYPE) }
        }
    }
}

@Composable
private fun ModeChip(
    label: String,
    selected: Boolean,
    tag: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
        contentColor =
            if (selected) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        modifier = Modifier.testTag(tag),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 7.dp),
        )
    }
}

/** 板块③：净资产趋势（蓝），月/年切换。 */
@Composable
private fun TrendPanel(
    state: AssetReportUiState,
    minHeight: Dp,
    onPeriod: (TrendPeriod) -> Unit,
) {
    val trend = state.trend
    var picked by remember(trend) { mutableIntStateOf(-1) }
    val points = trend.points
    val ratios = trend.ratios()

    SectionPanel(
        title = "净资产趋势",
        minHeight = minHeight,
        tag = "panel_trend",
        headerExtra = {
            SingleChoiceSegmentedButtonRow {
                TrendPeriod.entries.forEachIndexed { index, period ->
                    SegmentedButton(
                        selected = state.period == period,
                        onClick = { onPeriod(period) },
                        shape = SegmentedButtonDefaults.itemShape(index, TrendPeriod.entries.size),
                        modifier = Modifier.testTag("trend_period_${period.name.lowercase()}"),
                    ) {
                        Text(if (period == TrendPeriod.MONTH) "按月" else "按年")
                    }
                }
            }
        },
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = trend.latest?.let { MoneyFormatter.centsToYuan(it, showSymbol = true) } ?: "—",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = TrendBlue,
                modifier = Modifier.testTag("trend_latest"),
            )
            trend.change?.let { change ->
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "${if (change >= 0) "+" else "−"}¥${MoneyFormatter.centsToYuan(abs(change))}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (change >= 0) AssetGreen else LiabilityRed,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        val hint =
            if (picked in points.indices) {
                val p = points[picked]
                "${p.label}末：¥${MoneyFormatter.centsToYuan(p.net)}"
            } else {
                "点击曲线查看该时点净资产"
            }
        Text(
            text = hint,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("trend_hint"),
        )
        Spacer(Modifier.height(8.dp))
        ChartBox(height = 170.dp) {
            if (points.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "暂无数据",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LineChart(
                    // 净资产可为负：比例走 TrendPoint 的 min~max 归一化，不能用 ChartMath 的 0~max
                    points =
                        points.mapIndexed { index, p ->
                            ChartPoint(label = p.label, ratio = ratios[index], value = p.net)
                        },
                    modifier = Modifier.fillMaxSize(),
                    lineColor = TrendBlue,
                    fillColor = TrendBlue.copy(alpha = 0.14f),
                    selectedIndex = picked,
                    onPick = { picked = it },
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            trendLabelSample(points).forEach { label ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 从点位里等距抽最多 4 个标签；避免 12 个月挤成一坨。 */
internal fun trendLabelSample(points: List<TrendPoint>): List<String> {
    if (points.isEmpty()) return emptyList()
    if (points.size <= 4) return points.map { it.label }
    val step = (points.size - 1) / 3.0
    return (0..3).map { points[(it * step).toInt().coerceAtMost(points.lastIndex)].label }
}

/** 板块④：资产变化记录（金），时间倒序，点击流水可跳转。 */
@Composable
private fun ChangesPanel(
    state: AssetReportUiState,
    minHeight: Dp,
    onOpenTxn: (String) -> Unit,
) {
    SectionPanel(
        title = "资产变化记录",
        minHeight = minHeight,
        tag = "panel_changes",
        headerExtra = {
            Text(
                "共 ${state.changes.size} 条",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    ) {
        if (state.changes.isEmpty()) {
            Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                EmptyState(
                    title = "暂无变化",
                    description = "记账、改市值、改初始余额都会在这里留下一条记录；账户间转账不算变化",
                )
            }
        } else {
            state.changes.forEachIndexed { index, change ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                ChangeRow(change = change, onOpenTxn = onOpenTxn)
            }
        }
    }
}

@Composable
private fun ChangeRow(
    change: AssetChange,
    onOpenTxn: (String) -> Unit,
) {
    val positive = change.delta >= 0
    val txnId = change.txnId
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .then(if (txnId != null) Modifier.clickable { onOpenTxn(txnId) } else Modifier)
                .padding(vertical = 10.dp)
                .testTag("change_row"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = change.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = DateUtils.formatDateTime(change.at) + changeSubtitle(change),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = "${if (positive) "+" else "−"}¥${MoneyFormatter.centsToYuan(abs(change.delta))}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (positive) AssetGreen else LiabilityRed,
            )
            Text(
                text = "净资产 ¥${MoneyFormatter.centsToYuan(change.netAfter)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 元事件补一句「调整为 xx」，让「差额」与「目标值」都能看到（PRD D14）。 */
private fun changeSubtitle(change: AssetChange): String =
    when (change.kind) {
        AssetChangeKind.MARKET_VALUE, AssetChangeKind.INITIAL_BALANCE ->
            change.amountAfter?.let { " · 调整为 ¥${MoneyFormatter.centsToYuan(it)}" } ?: ""
        else -> ""
    }
