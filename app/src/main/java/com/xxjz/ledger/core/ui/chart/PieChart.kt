package com.xxjz.ledger.core.ui.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 环形饼图（P07 消费构成）。
 *
 * 纯函数组件：只接收已算好的 [PieSlice] 列表，不持有状态、不查库（DEV_PLAN M5 技术点）。
 * 数据为空或全为 0 时不画圈，交由调用方显示空状态；这里额外返回 false 让上层处理。
 *
 * @param selected 高亮下标，-1 表示无
 * @param onSelect 点击某瓣回调，参数为下标；点击空白处回调 -1
 */
@Composable
fun PieChart(
    slices: List<PieSlice>,
    modifier: Modifier = Modifier,
    thickness: Dp = 26.dp,
    selected: Int = -1,
    onSelect: (Int) -> Unit = {},
    centerContent: @Composable () -> Unit = {},
) {
    val values = slices.map { it.value }
    val arcs = PieGeometry.arcs(values)
    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        val size = minOf(maxWidth, maxHeight)
        val radiusPx = with(androidx.compose.ui.platform.LocalDensity.current) { (size / 2).toPx() }
        val innerPx = with(androidx.compose.ui.platform.LocalDensity.current) { (size / 2 - thickness).toPx() }
        Canvas(
            modifier =
                Modifier
                    .size(size)
                    .pointerInput(arcs) {
                        detectTapGestures { offset ->
                            val hit =
                                PieGeometry.hitTest(
                                    arcs = arcs,
                                    centerX = this.size.width / 2f,
                                    centerY = this.size.height / 2f,
                                    radius = this.size.width / 2f,
                                    innerRadius = innerPx,
                                    tapX = offset.x,
                                    tapY = offset.y,
                                )
                            onSelect(hit ?: -1)
                        }
                    },
        ) {
            if (arcs.isEmpty()) return@Canvas
            val topLeft = Offset((this.size.width - radiusPx * 2) / 2f, (this.size.height - radiusPx * 2) / 2f)
            val arcSize = Size(radiusPx * 2, radiusPx * 2)
            arcs.forEach { arc ->
                val base = slices[arc.index].color.toComposeColor()
                val color = if (selected < 0 || selected == arc.index) base else base.copy(alpha = 0.35f)
                // 选中瓣轻微外扩，用描边宽度模拟
                val width = if (selected == arc.index) thickness.toPx() * 1.35f else thickness.toPx()
                val inset = (thickness.toPx() - width) / 2f
                drawArc(
                    color = color,
                    startAngle = arc.startAngle,
                    sweepAngle = arc.sweepAngle,
                    useCenter = false,
                    topLeft = topLeft + Offset(inset, inset),
                    size = Size(arcSize.width - inset * 2, arcSize.height - inset * 2),
                    style = Stroke(width = width),
                )
            }
        }
        Box(modifier = Modifier.padding(horizontal = 48.dp), contentAlignment = Alignment.Center) {
            centerContent()
        }
    }
}

/** 空态占位环：数据为 0 时提示，而不是画一个灰整圆。 */
@Composable
fun EmptyRing(
    modifier: Modifier = Modifier,
    thickness: Dp = 26.dp,
) {
    val color = MaterialTheme.colorScheme.outlineVariant
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val r = minOf(size.width, size.height) / 2f
            drawCircle(
                color = color.copy(alpha = 0.5f),
                radius = r - thickness.toPx() / 2,
                style = Stroke(width = thickness.toPx() * 0.6f),
            )
        }
    }
}

/** ARGB 长整型转 Compose Color（`Color.value` 是 ColorSpace 编码，不能直接当 ARGB 用）。 */
fun Long.toComposeColor(): Color = Color(this.toInt())

/** Compose Color 转 ARGB 长整型，供 `PieSlice.color` 存储。 */
fun Color.toArgbLong(): Long = this.toArgb().toLong()

fun String.toComposeColor(): Color = runCatching { Color(android.graphics.Color.parseColor(this)) }.getOrDefault(Color(0xFF9E9E9E))
