package com.xxjz.ledger.core.ui.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 单折线图（P07 趋势）。
 *
 * 纯函数组件：数据已在 usecase 里算好比例（[ChartPoint.ratio]），这里只画
 * （DEV_PLAN M5 技术点：年视图数据量大，不在重组里算）。
 *
 * @param onPick 点击某点时回调下标，用于显示金额提示
 */
@Composable
fun LineChart(
    points: List<ChartPoint>,
    modifier: Modifier = Modifier,
    lineColor: Color = MaterialTheme.colorScheme.primary,
    fillColor: Color = lineColor.copy(alpha = 0.14f),
    selectedIndex: Int = -1,
    onPick: (Int) -> Unit = {},
    showGrid: Boolean = true,
) {
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelCount = 4
    Canvas(
        modifier =
            modifier.pointerInput(points.size) {
                detectTapGestures { offset ->
                    if (points.isEmpty()) return@detectTapGestures
                    val step = size.width.toFloat() / (points.size - 1).coerceAtLeast(1)
                    val index = ((offset.x / step).toInt()).coerceIn(0, points.lastIndex)
                    onPick(index)
                }
            },
    ) {
        if (points.isEmpty()) return@Canvas
        val padTop = 8.dp.toPx()
        val padBottom = 18.dp.toPx()
        val h = size.height - padTop - padBottom
        val step = if (points.size > 1) size.width / (points.size - 1) else size.width

        if (showGrid) {
            repeat(labelCount) { i ->
                val y = padTop + h * i / (labelCount - 1f)
                drawLine(
                    color = gridColor,
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 1f,
                )
            }
        }

        val path = Path()
        val fill = Path()
        points.forEachIndexed { index, point ->
            val x = step * index
            val y = padTop + h * (1f - point.ratio)
            if (index == 0) {
                path.moveTo(x, y)
                fill.moveTo(x, padTop + h)
                fill.lineTo(x, y)
            } else {
                path.lineTo(x, y)
                fill.lineTo(x, y)
            }
        }
        fill.lineTo(size.width, padTop + h)
        fill.close()

        drawPath(fill, color = fillColor)
        drawPath(path, color = lineColor, style = Stroke(width = 2.5.dp.toPx()))

        // 只画选中点或点时较少时画全部点，避免密集视图糊成一片
        val dotEvery = if (points.size > 40) 5 else 1
        points.forEachIndexed { index, point ->
            if (index % dotEvery != 0 && index != selectedIndex) return@forEachIndexed
            val x = step * index
            val y = padTop + h * (1f - point.ratio)
            val r = if (index == selectedIndex) 5.dp.toPx() else 2.5.dp.toPx()
            drawCircle(color = lineColor, radius = r, center = Offset(x, y))
        }
    }
}

/**
 * 双柱状图（P09 年度 12 月收支）。
 *
 * @param seriesA 主序列（支出）
 * @param seriesB 副序列（收入），可为空
 */
@Composable
fun BarChart(
    points: List<ChartPoint>,
    modifier: Modifier = Modifier,
    barColor: Color = MaterialTheme.colorScheme.primary,
    secondaryPoints: List<ChartPoint> = emptyList(),
    secondaryColor: Color = MaterialTheme.colorScheme.tertiary,
    selectedIndex: Int = -1,
    onPick: (Int) -> Unit = {},
) {
    val axisColor = MaterialTheme.colorScheme.outlineVariant
    val dual = secondaryPoints.isNotEmpty()
    Canvas(
        modifier =
            modifier.pointerInput(points.size, dual) {
                detectTapGestures { offset ->
                    if (points.isEmpty()) return@detectTapGestures
                    val slot = size.width.toFloat() / points.size
                    val index = (offset.x / slot).toInt().coerceIn(0, points.lastIndex)
                    onPick(index)
                }
            },
    ) {
        if (points.isEmpty()) return@Canvas
        val padBottom = 16.dp.toPx()
        val padTop = 6.dp.toPx()
        val h = size.height - padBottom - padTop
        val slot = size.width / points.size
        val groupGap = slot * 0.12f
        val count = if (dual) 2 else 1
        val barW = (slot - groupGap * 2) / count

        drawLine(
            color = axisColor,
            start = Offset(0f, padTop + h),
            end = Offset(size.width, padTop + h),
            strokeWidth = 1f,
        )

        points.forEachIndexed { index, point ->
            val left = index * slot + groupGap
            val barH = h * point.ratio
            val highlight = selectedIndex == index
            drawRect(
                color = if (highlight) barColor else barColor.copy(alpha = 0.9f),
                topLeft = Offset(left, padTop + h - barH.coerceAtLeast(point.ratio.takeIf { it > 0 } ?: 0f)),
                size = Size(barW, barH.coerceAtLeast(if (point.value > 0) 2.dp.toPx() else 0f)),
            )
            if (dual) {
                val second = secondaryPoints.getOrNull(index)
                if (second != null) {
                    val h2 = h * second.ratio
                    drawRect(
                        color = secondaryColor.copy(alpha = 0.85f),
                        topLeft = Offset(left + barW, padTop + h - h2),
                        size = Size(barW, h2.coerceAtLeast(if (second.value > 0) 2.dp.toPx() else 0f)),
                    )
                }
            }
        }
    }
}

/** 图例小方块 + 文案由调用方排布，这里只提供色块。 */
@Composable
fun LegendDot(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 8.dp,
) {
    Canvas(modifier = modifier.height(size)) {
        drawCircle(color = color, radius = size.toPx() / 2, center = center)
    }
}

/** 图表宽高比容器：保证 Canvas 有确定高度。 */
@Composable
fun ChartBox(
    height: Dp = 180.dp,
    content: @Composable () -> Unit,
) {
    Box(modifier = Modifier.fillMaxWidth().height(height)) { content() }
}
