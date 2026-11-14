package com.xxjz.ledger.feature.report

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.xxjz.ledger.core.designsystem.component.EmptyState
import com.xxjz.ledger.core.ui.chart.ChartBox
import com.xxjz.ledger.core.ui.chart.ChartPoint
import com.xxjz.ledger.core.ui.chart.EmptyRing
import com.xxjz.ledger.core.ui.chart.LineChart
import com.xxjz.ledger.core.ui.chart.PieChart
import com.xxjz.ledger.core.ui.chart.PieSlice
import com.xxjz.ledger.core.ui.chart.toArgbLong
import com.xxjz.ledger.domain.usecase.AssetChange
import com.xxjz.ledger.domain.usecase.AssetChangeKind
import com.xxjz.ledger.domain.usecase.AssetSlice
import com.xxjz.ledger.domain.usecase.TrendPeriod
import com.xxjz.ledger.domain.usecase.TrendPoint
import kotlin.math.abs

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
    viewModel: AssetReportViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // 每块至少半屏高：小屏上一屏放两块，大屏上留白而不是把内容拉伸变形
    val halfScreen = (LocalConfiguration.current.screenHeightDp / 2).dp

    Scaffold(
        modifier = Modifier.fillMaxSize().testTag("screen_asset_report"),
        topBar = {
            TopAppBar(
                title = { Text("资产报表") },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("asset_report_back")) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        if (state.loading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            NetWorthHeadline(net = state.net, assets = state.assets.total, liability = state.liability.total)
            AssetPanel(state, halfScreen)
            LiabilityPanel(state, halfScreen)
            TrendPanel(state, halfScreen, onPeriod = viewModel::setPeriod)
            ChangesPanel(state, halfScreen, onOpenTxn)
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** 四板块的描边色（D12：绿 / 红 / 蓝 / 金）。 */
private val AssetGreen = Color(0xFF2E9E6B)
private val LiabilityRed = Color(0xFFD9534F)
private val TrendBlue = Color(0xFF3B7DD8)
private val ChangeGold = Color(0xFFC99A2E)

/**
 * 板块容器：统一「描边 + 标题 + 半屏最小高度」，四个调用点只关心内容。
 *
 * @param tag 测试与 UI 走查用的稳定标识
 */
@Composable
private fun SectionPanel(
    title: String,
    accent: Color,
    minHeight: Dp,
    tag: String,
    headerExtra: @Composable (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().heightIn(min = minHeight).testTag(tag),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.5.dp, accent.copy(alpha = 0.65f)),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(accent))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
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

/** 顶部抬头：净资产一个大数字，两侧资产/负债小字（与账户页口径完全一致）。 */
@Composable
private fun NetWorthHeadline(
    net: Long,
    assets: Long,
    liability: Long,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "净资产",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = MoneyFormatter.centsToYuan(net, showSymbol = true),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.testTag("asset_report_net"),
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                MiniAmount("总资产", assets, AssetGreen)
                MiniAmount("总负债", liability, LiabilityRed)
            }
        }
    }
}

@Composable
private fun MiniAmount(
    label: String,
    cents: Long,
    color: Color,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(4.dp))
        Text(
            text = MoneyFormatter.centsToYuan(cents, showSymbol = true),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = color,
        )
    }
}

/** 板块①：资产构成（绿）。 */
@Composable
private fun AssetPanel(
    state: AssetReportUiState,
    minHeight: Dp,
) {
    val slices = state.assets.nonZero
    SectionPanel(
        title = "资产构成",
        accent = AssetGreen,
        minHeight = minHeight,
        tag = "panel_assets",
        headerExtra = {
            Text(
                MoneyFormatter.centsToYuan(state.assets.total, showSymbol = true),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = AssetGreen,
            )
        },
    ) {
        if (slices.isEmpty()) {
            Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                EmptyState(title = "还没有资产", description = "新建账户并记上几笔账，这里就会显示构成占比")
            }
        } else {
            BreakdownPie(slices = slices, total = state.assets.total, tag = "pie_assets")
            if (state.assets.excludedInvest > 0) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "另有投资市值 ${MoneyFormatter.centsToYuan(state.assets.excludedInvest, showSymbol = true)} 未计入净资产",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("invest_excluded_hint"),
                )
            }
        }
    }
}

/** 板块②：负债构成（红）。 */
@Composable
private fun LiabilityPanel(
    state: AssetReportUiState,
    minHeight: Dp,
) {
    val slices = state.liability.nonZero
    SectionPanel(
        title = "负债构成",
        accent = LiabilityRed,
        minHeight = minHeight,
        tag = "panel_liability",
        headerExtra = {
            Text(
                MoneyFormatter.centsToYuan(state.liability.total, showSymbol = true),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = LiabilityRed,
            )
        },
    ) {
        if (slices.isEmpty()) {
            Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                EmptyState(title = "没有负债", description = "信用卡欠款、借贷未结清、储蓄卡透支都会汇总到这里")
            }
        } else {
            BreakdownPie(
                slices = slices,
                total = state.liability.total,
                tag = "pie_liability",
                palette = listOf(LiabilityRed, Color(0xFFE08A5F), Color(0xFFB4568C)),
            )
        }
    }
}

/**
 * 饼图 + 图边占比标注 + 点击高亮金额。
 *
 * 占比数字标在图**旁边的图例行**上（PRD 5.2「占比数字直接标注在图边」），
 * 点击扇区把中心文案换成该段金额，再点一次取消。
 */
@Composable
private fun BreakdownPie(
    slices: List<AssetSlice>,
    total: Long,
    tag: String,
    palette: List<Color> = listOf(AssetGreen, Color(0xFF4FA3D1), Color(0xFF2E7D5B)),
) {
    var selected by remember(slices) { mutableIntStateOf(-1) }
    val pieSlices =
        slices.mapIndexed { index, slice ->
            PieSlice(
                label = slice.label,
                value = slice.amount,
                color = palette[index % palette.size].toArgbLong(),
            )
        }
    val active = slices.getOrNull(selected)

    Column {
        Box(Modifier.fillMaxWidth().testTag(tag), contentAlignment = Alignment.Center) {
            if (pieSlices.isEmpty()) {
                EmptyRing(modifier = Modifier.size(180.dp))
            } else {
                PieChart(
                    slices = pieSlices,
                    modifier = Modifier.size(200.dp),
                    selected = selected,
                    onSelect = { selected = if (it == selected) -1 else it },
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = active?.label ?: "合计",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "¥${MoneyFormatter.centsToYuan(active?.amount ?: total)}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (active != null) {
                            Text(
                                text = "${active.percent(total)}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        slices.forEachIndexed { index, slice ->
            val color = palette[index % palette.size]
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable { selected = if (selected == index) -1 else index }
                        .padding(vertical = 10.dp)
                        .testTag("${tag}_legend_$index"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(color))
                Spacer(Modifier.width(8.dp))
                Text(slice.label, style = MaterialTheme.typography.bodyMedium)
                Box(Modifier.weight(1f))
                Text(
                    text = "${slice.percent(total)}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = MoneyFormatter.centsToYuan(slice.amount, showSymbol = true),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = color,
                )
            }
        }
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
        accent = TrendBlue,
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
        accent = ChangeGold,
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
