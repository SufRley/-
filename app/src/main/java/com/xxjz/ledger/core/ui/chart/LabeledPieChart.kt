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

/**
 * 饼图外侧的引导标签：账户名 + 数值（v1.9.3 第 5 条）。
 *
 * 以前是「名字一行、占比一行」两行固定排版；改成一行 `名字 数值`，
 * 数值内容由调用方按当前模式给（金额或百分比）。
 */
data class PieCallout(
    val title: String,
    val value: String,
)

/**
 * 引导线的中间状态。
 *
 * 先由 [layoutCallouts] 算好纵向位置与文字宽度，再交给画线与画字两步共用，
 * 避免在三个地方各算一遍几何。
 */
private class CalloutItem(
    val index: Int,
    val sin: Float,
    val cos: Float,
    val color: Color,
) {
    var labelY: Float = 0f
    var height: Float = 0f
    var direction: Int = 1
    var alpha: Float = 1f
    var title: String = ""
    var value: String = ""
    var singleLine: Boolean = true
    var textWidth: Float = 0f
}

/**
 * 带引导线的环形饼图（PRD v1.9.0 第 1 条，v1.9.3 第 5 条重排）。
 *
 * 与 [PieChart] 的唯一区别：每个色块都用一条折线连到外侧的文字标签，
 * 看饼图时不用再去下面图例里找「这块深绿是哪个账户」。
 *
 * 排版规则（参照实机截图）：
 * - 标签**贴左右边缘**，两侧都左对齐，左列从 x=0、右列贴画布右边；
 * - 每条标签默认一行 `名字 数值`，名字太长放不下时折成两行；
 * - 防重叠：按扇区中角的左右分量分两列，各自按 y 排序后按**各自高度**递推最小间距，
 *   再夹回画布范围。
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
    val textSize = with(density) { 11.sp.toPx() }
    val titlePaint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT } }
    val valuePaint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD } }
    val titleColor = MaterialTheme.colorScheme.onSurfaceVariant
    val valueColor = MaterialTheme.colorScheme.onSurface

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        // 高度定死，标签的纵向排布才有稳定基准
        val chartHeight = 300.dp
        // 标签列宽：截图里标签约占卡片的四分之一强，88dp 能放下「投资账户 2,000.00」
        val calloutSpace = 88.dp
        val gap = 6.dp
        val outerRadius =
            with(density) {
                min(
                    maxWidth.toPx() / 2f - calloutSpace.toPx(),
                    chartHeight.toPx() / 2f - 16.dp.toPx(),
                )
            }.coerceAtLeast(40f)
        val thicknessPx = with(density) { thickness.toPx() }
        val innerRadius = (outerRadius - thicknessPx).coerceAtLeast(1f)
        val textGapPx = with(density) { gap.toPx() }

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

                titlePaint.textSize = textSize
                valuePaint.textSize = textSize
                val maxTextWidth = center.x - (outerRadius + 12.dp.toPx()) - textGapPx

                val items =
                    layoutCallouts(
                        arcs = arcs,
                        slices = slices,
                        callouts = callouts,
                        center = center,
                        outerRadius = outerRadius,
                        maxTextWidth = maxTextWidth,
                        titlePaint = titlePaint,
                        valuePaint = valuePaint,
                        textSize = textSize,
                    )
                drawCalloutLines(items, selected, center, outerRadius, textGapPx)
                drawCalloutText(items, titlePaint, valuePaint, titleColor, valueColor, textSize, size.width)
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
    callouts: List<PieCallout>,
    center: Offset,
    outerRadius: Float,
    maxTextWidth: Float,
    titlePaint: Paint,
    valuePaint: Paint,
    textSize: Float,
): List<CalloutItem> {
    val lineGap = 12.dp.toPx()
    val lineGapV = 6.dp.toPx()
    val items =
        arcs.map { arc ->
            val rad = Math.toRadians(arc.midAngle.toDouble())
            val item =
                CalloutItem(
                    index = arc.index,
                    sin = sin(rad).toFloat(),
                    cos = cos(rad).toFloat(),
                    color = slices[arc.index].color.toComposeColor(),
                )
            // 先量文字：决定单行还是两行，进而决定这一条占多高
            val callout = callouts[arc.index]
            val full = "${callout.title} ${callout.value}"
            val fits = titlePaint.measureText(full) <= maxTextWidth
            if (fits) {
                item.singleLine = true
                item.title = callout.title
                item.value = callout.value
                item.textWidth = titlePaint.measureText(full)
                item.height = textSize + lineGapV
            } else {
                item.singleLine = false
                item.title = titlePaint.ellipsize(callout.title, maxTextWidth)
                item.value = callout.value
                item.textWidth =
                    max(titlePaint.measureText(item.title), valuePaint.measureText(callout.value))
                item.height = textSize * 2 + lineGapV + 2.dp.toPx()
            }
            item
        }
    val top = textSize + 2.dp.toPx()
    val bottom = center.y * 2f
    placeSide(items.filter { it.cos >= 0f }, center.y, outerRadius, top, bottom)
    placeSide(items.filter { it.cos < 0f }, center.y, outerRadius, top, bottom)
    items.forEach { item ->
        item.direction = if (item.cos >= 0f) 1 else -1
    }
    return items
}

/**
 * 单侧的纵向排布：先按角度给目标位置，再按**前一条的实际高度**递推最小间距，最后整体夹回边界。
 *
 * 三步缺一不可——只做行距会把整列推出画布，只夹边界又会重新重叠。
 * 递推时用的是上一条自己的高度，单行和两行的标签混排才不会互相压住。
 */
private fun placeSide(
    items: List<CalloutItem>,
    centerY: Float,
    outerRadius: Float,
    top: Float,
    bottom: Float,
) {
    if (items.isEmpty()) return
    val sorted = items.sortedBy { it.sin }
    sorted.forEach { item ->
        item.labelY = (centerY + item.sin * (outerRadius + 14f)).coerceIn(top, bottom - item.height)
    }
    for (index in 1 until sorted.size) {
        val prev = sorted[index - 1]
        val current = sorted[index]
        current.labelY = max(current.labelY, prev.labelY + prev.height)
    }
    if (sorted.last().labelY > bottom - sorted.last().height) {
        sorted.last().labelY = bottom - sorted.last().height
        for (index in sorted.size - 2 downTo 0) {
            val next = sorted[index + 1]
            val current = sorted[index]
            current.labelY = min(current.labelY, next.labelY - current.height)
        }
        sorted.first().labelY = max(sorted.first().labelY, top)
    }
}

/**
 * 画折线：从环外缘出发 → 肘部 → 水平到文字边缘。
 *
 * 水平段的终点由**文字实际宽度**决定，不能用列宽：左对齐之后短标签右侧会留白，
 * 从列边缘起画会让引线凭空漂在空白里。
 */
private fun DrawScope.drawCalloutLines(
    items: List<CalloutItem>,
    selected: Int,
    center: Offset,
    outerRadius: Float,
    textGapPx: Float,
) {
    val lineGap = 12.dp.toPx()
    items.forEach { item ->
        val alpha = if (selected < 0 || selected == item.index) 1f else 0.35f
        val start = Offset(center.x + item.cos * outerRadius, center.y + item.sin * outerRadius)
        val elbowX = center.x + item.direction * (outerRadius + lineGap)
        // 引线在文字那一侧收住：右列从文字左边缘往左、左列从文字右边缘往右
        val textEdge =
            if (item.direction > 0) {
                size.width - item.textWidth - textGapPx
            } else {
                item.textWidth + textGapPx
            }
        val path =
            Path().apply {
                moveTo(start.x, start.y)
                lineTo(elbowX, item.labelY)
                lineTo(textEdge, item.labelY)
            }
        drawPath(path, color = item.color.copy(alpha = 0.8f * alpha), style = Stroke(1.2.dp.toPx()))
        // 起点小圆点，让线看起来是从色块里长出来的
        drawCircle(color = item.color.copy(alpha = alpha), radius = 2.dp.toPx(), center = start)
        item.alpha = alpha
    }
}

/**
 * 画文字：贴左右边缘，两侧都左对齐。
 *
 * 单行时 `名字` 用次色、`数值` 用主色跟着画在后面；两行时数值落到下一行。
 * 左列从 x=0 起，右列从 `画布宽 - 文字宽` 起——这样两列的文字外边缘都与卡片边界对齐。
 */
private fun DrawScope.drawCalloutText(
    items: List<CalloutItem>,
    titlePaint: Paint,
    valuePaint: Paint,
    titleColor: Color,
    valueColor: Color,
    textSize: Float,
    canvasWidth: Float,
) {
    val canvas = drawContext.canvas.nativeCanvas
    items.forEach { item ->
        val x = if (item.direction > 0) canvasWidth - item.textWidth else 0f
        titlePaint.color = titleColor.copy(alpha = item.alpha).toArgb()
        valuePaint.color = valueColor.copy(alpha = item.alpha).toArgb()
        if (item.singleLine) {
            canvas.drawText(item.title, x, item.labelY, titlePaint)
            val offset = titlePaint.measureText("${item.title} ")
            canvas.drawText(item.value, x + offset, item.labelY, valuePaint)
        } else {
            canvas.drawText(item.title, x, item.labelY, titlePaint)
            canvas.drawText(item.value, x, item.labelY + textSize + 2.dp.toPx(), valuePaint)
        }
    }
}

/** 超宽截断：末尾补省略号，宽度压到 [maxWidth] 以内。 */
private fun Paint.ellipsize(
    text: String,
    maxWidth: Float,
): String {
    if (text.isEmpty() || measureText(text) <= maxWidth) return text
    val ellipsis = "…"
    var end = text.length - 1
    while (end > 0 && measureText(text.substring(0, end) + ellipsis) > maxWidth) {
        end--
    }
    return if (end <= 0) ellipsis else text.substring(0, end) + ellipsis
}
