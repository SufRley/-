package com.xxjz.ledger.core.ui.chart

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** 饼图外侧的引导标签：账户名 + 占比。 */
data class PieCallout(
    val title: String,
    val subtitle: String,
)

/**
 * 引导线的中间状态。
 *
 * 先由 [layoutCallouts] 算好纵向位置与肘部横坐标，再交给画线与画字两步共用，
 * 避免在三个地方各算一遍几何。
 */
private class CalloutItem(
    val index: Int,
    val sin: Float,
    val cos: Float,
    val color: Color,
) {
    var labelY: Float = 0f
    var elbowX: Float = 0f
    var direction: Int = 1
    var alpha: Float = 1f
}

/**
 * 带引导线的环形饼图（PRD v1.9.0 第 1 条）。
 *
 * 与 [PieChart] 的唯一区别：每个色块都用一条折线连到外侧的文字标签，
 * 看饼图时不用再去下面图例里找「这块深绿是哪个账户」。
 *
 * 防重叠：按扇区中角的左右分量分两列，各自按 y 排序后强制最小行距，再夹回画布范围。
 * 八段以内、360dp 宽的屏上都能读清。
 */
@Composable
fun LabeledPieChart(
    slices: List<PieSlice>,
    callouts: List<PieCallout>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    thickness: Dp = 24.dp,
    centerContent: @Composable () -> Unit = {},
) {
    val arcs = PieGeometry.arcs(slices.map { it.value })
    val density = LocalDensity.current
    val titleSize = with(density) { 11.sp.toPx() }
    val subSize = with(density) { 10.sp.toPx() }
    val titlePaint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT } }
    val subPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT } }
    val titleColor = MaterialTheme.colorScheme.onSurface
    val subColor = MaterialTheme.colorScheme.onSurfaceVariant

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        // 高度定死，标签的纵向排布才有稳定基准
        val chartHeight = 300.dp
        val calloutSpace = 78.dp
        val outerRadius =
            with(density) {
                min(
                    maxWidth.toPx() / 2f - calloutSpace.toPx(),
                    chartHeight.toPx() / 2f - 16.dp.toPx(),
                )
            }
        val thicknessPx = with(density) { thickness.toPx() }
        val innerRadius = (outerRadius - thicknessPx).coerceAtLeast(1f)

        Box(Modifier.fillMaxWidth().height(chartHeight), contentAlignment = Alignment.Center) {
            Canvas(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .pointerInput(arcs, outerRadius) {
                            detectTapGestures { offset ->
                                val hit =
                                    PieGeometry.hitTest(
                                        arcs = arcs,
                                        centerX = size.width / 2f,
                                        centerY = size.height / 2f,
                                        radius = outerRadius,
                                        innerRadius = innerRadius,
                                        tapX = offset.x,
                                        tapY = offset.y,
                                    )
                                onSelect(hit ?: -1)
                            }
                        },
            ) {
                if (arcs.isEmpty() || callouts.size < arcs.size) return@Canvas
                val center = Offset(size.width / 2f, size.height / 2f)
                drawRing(arcs, slices, selected, center, outerRadius, thicknessPx)
                val items = layoutCallouts(arcs, slices, center, outerRadius, titleSize, subSize)
                drawCalloutLines(items, selected, center, outerRadius)
                drawCalloutText(items, callouts, titlePaint, subPaint, titleSize, subSize, titleColor, subColor)
            }
            // 圆心内容：宽高按内圈直径给，避免文字压到环上
            Box(
                modifier = Modifier.size(with(density) { (innerRadius * 2f).toDp() }),
                contentAlignment = Alignment.Center,
            ) {
                centerContent()
            }
        }
    }
}

/** 画环：选中段加粗、其余段压暗，与 [PieChart] 观感一致。 */
private fun DrawScope.drawRing(
    arcs: List<SliceArc>,
    slices: List<PieSlice>,
    selected: Int,
    center: Offset,
    outerRadius: Float,
    thicknessPx: Float,
) {
    arcs.forEach { arc ->
        val base = slices[arc.index].color.toComposeColor()
        val color = if (selected < 0 || selected == arc.index) base else base.copy(alpha = 0.35f)
        val width = if (selected == arc.index) thicknessPx * 1.25f else thicknessPx
        val mid = outerRadius - thicknessPx / 2f
        drawArc(
            color = color,
            startAngle = arc.startAngle,
            sweepAngle = arc.sweepAngle,
            useCenter = false,
            topLeft = Offset(center.x - mid, center.y - mid),
            size = Size(mid * 2, mid * 2),
            style = Stroke(width = width),
        )
    }
}

/**
 * 算好每条引导线的肘部位置与标签纵坐标。
 *
 * 分列依据是扇区中角的 cos 分量：正值走右半屏、负值走左半屏，引线不会横穿饼图。
 */
private fun DrawScope.layoutCallouts(
    arcs: List<SliceArc>,
    slices: List<PieSlice>,
    center: Offset,
    outerRadius: Float,
    titleSize: Float,
    subSize: Float,
): List<CalloutItem> {
    val lineGap = 12.dp.toPx()
    val lineHeight = titleSize + subSize + 6.dp.toPx()
    val items =
        arcs.map { arc ->
            val rad = Math.toRadians(arc.midAngle.toDouble())
            CalloutItem(
                index = arc.index,
                sin = sin(rad).toFloat(),
                cos = cos(rad).toFloat(),
                color = slices[arc.index].color.toComposeColor(),
            )
        }
    val top = titleSize + 2.dp.toPx()
    val bottom = center.y * 2f - lineHeight
    placeSide(items.filter { it.cos >= 0f }, center.y, outerRadius, lineHeight, top, bottom)
    placeSide(items.filter { it.cos < 0f }, center.y, outerRadius, lineHeight, top, bottom)
    items.forEach { item ->
        item.direction = if (item.cos >= 0f) 1 else -1
        item.elbowX = center.x + item.direction * (outerRadius + lineGap)
    }
    return items
}

/**
 * 单侧的纵向排布：先按角度给目标位置，再强制最小行距，最后整体夹回边界。
 *
 * 三步缺一不可——只做行距会把整列推出画布，只夹边界又会重新重叠。
 */
private fun placeSide(
    items: List<CalloutItem>,
    centerY: Float,
    outerRadius: Float,
    lineHeight: Float,
    top: Float,
    bottom: Float,
) {
    if (items.isEmpty()) return
    val sorted = items.sortedBy { it.sin }
    sorted.forEach { item ->
        item.labelY = (centerY + item.sin * (outerRadius + 14f)).coerceIn(top, bottom)
    }
    for (index in 1 until sorted.size) {
        val prev = sorted[index - 1]
        val current = sorted[index]
        current.labelY = max(current.labelY, prev.labelY + lineHeight)
    }
    if (sorted.last().labelY > bottom) {
        sorted.last().labelY = bottom
        for (index in sorted.size - 2 downTo 0) {
            val next = sorted[index + 1]
            val current = sorted[index]
            current.labelY = min(current.labelY, next.labelY - lineHeight)
        }
        sorted.first().labelY = max(sorted.first().labelY, top)
    }
}

/** 画折线：从环外缘出发 → 肘部 → 一小段水平，末端就是文字锚点。 */
private fun DrawScope.drawCalloutLines(
    items: List<CalloutItem>,
    selected: Int,
    center: Offset,
    outerRadius: Float,
) {
    val lineGap = 12.dp.toPx()
    items.forEach { item ->
        val alpha = if (selected < 0 || selected == item.index) 1f else 0.35f
        val start = Offset(center.x + item.cos * outerRadius, center.y + item.sin * outerRadius)
        val endX = item.elbowX + item.direction * lineGap * 0.6f
        val path =
            Path().apply {
                moveTo(start.x, start.y)
                lineTo(item.elbowX, item.labelY)
                lineTo(endX, item.labelY)
            }
        drawPath(path, color = item.color.copy(alpha = 0.8f * alpha), style = Stroke(1.2.dp.toPx()))
        // 起点小圆点，让线看起来是从色块里长出来的
        drawCircle(color = item.color.copy(alpha = alpha), radius = 2.dp.toPx(), center = start)
        item.alpha = alpha
    }
}

/**
 * 画两行文字：上行账户名、下行占比。
 *
 * 右侧左对齐、左侧右对齐——都靠 `measureText` 反推起点，这样两列文字的「内侧边」
 * 都贴着引线末端，视觉上才是对称的。
 */
private fun DrawScope.drawCalloutText(
    items: List<CalloutItem>,
    callouts: List<PieCallout>,
    titlePaint: Paint,
    subPaint: Paint,
    titleSize: Float,
    subSize: Float,
    titleColor: Color,
    subColor: Color,
) {
    val gap = 8.dp.toPx()
    titlePaint.textSize = titleSize
    subPaint.textSize = subSize
    items.forEach { item ->
        val callout = callouts[item.index]
        val width = max(titlePaint.measureText(callout.title), subPaint.measureText(callout.subtitle))
        val anchor = item.elbowX + item.direction * gap
        val textX = if (item.direction > 0) anchor else anchor - width
        titlePaint.color = titleColor.copy(alpha = item.alpha).toArgb()
        subPaint.color = subColor.copy(alpha = item.alpha).toArgb()
        val canvas = drawContext.canvas.nativeCanvas
        canvas.drawText(callout.title, textX, item.labelY, titlePaint)
        canvas.drawText(callout.subtitle, textX, item.labelY + subSize + 3.dp.toPx(), subPaint)
    }
}
