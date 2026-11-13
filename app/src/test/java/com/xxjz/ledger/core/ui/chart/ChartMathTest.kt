package com.xxjz.ledger.core.ui.chart

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** M5 图表几何（AC-37 饼图命中、AC-40 趋势比例）。 */
@DisplayName("图表几何")
class ChartMathTest {
    @Test
    @DisplayName("扇区从 12 点方向顺时针铺开，总角度 360°")
    fun arcsStartAtTop() {
        val arcs = PieGeometry.arcs(listOf(25L, 25L, 50L))
        assertEquals(3, arcs.size)
        assertEquals(-90f, arcs.first().startAngle, 0.01f)
        // 前两瓣各 25% → 各 90°，拼满右半边
        assertEquals(90f, arcs[0].sweepAngle, 0.01f)
        assertEquals(90f, arcs[1].sweepAngle, 0.01f)
        assertEquals(180f, arcs[2].sweepAngle, 0.01f)
        assertEquals(0f, arcs[0].endAngle, 0.01f)
        assertEquals(0f, arcs[1].startAngle, 0.01f)
        assertEquals(90f, arcs[1].endAngle, 0.01f)
        assertEquals(90f, arcs[2].startAngle, 0.01f)
        // 末瓣收在 270°，等价于 -90°（12 点方向），整圆闭合
        assertEquals(270f, arcs[2].endAngle, 0.01f)
    }

    @Test
    @DisplayName("总和为 0 时返回空扇区（不画空圈）")
    fun zeroValuesProduceNoArc() {
        assertTrue(PieGeometry.arcs(listOf(0L, 0L)).isEmpty())
        assertTrue(PieGeometry.arcs(emptyList()).isEmpty())
    }

    @Test
    @DisplayName("点击命中：根据角度判断落在哪一瓣")
    fun hitTestByAngle() {
        val arcs = PieGeometry.arcs(listOf(25L, 25L, 50L))
        // 第一瓣 -90°~0°（右上），点 (cx+50, cy-50) 方向为 -45°
        assertEquals(0, PieGeometry.hitTest(arcs, 100f, 100f, 100f, 40f, 150f, 50f))
        // 第三瓣 90°~270°（左侧），点 (cx-50, cy) 方向为 180°
        assertEquals(2, PieGeometry.hitTest(arcs, 100f, 100f, 100f, 40f, 50f, 100f))
    }

    @Test
    @DisplayName("点击在于环外或圆心空洞里不算命中")
    fun hitTestOutside() {
        val arcs = PieGeometry.arcs(listOf(50L, 50L))
        assertNull(PieGeometry.hitTest(arcs, 100f, 100f, 100f, 40f, 300f, 100f))
        assertNull(PieGeometry.hitTest(arcs, 100f, 100f, 100f, 40f, 105f, 105f))
    }

    @Test
    @DisplayName("空扇区时点击不命中")
    fun hitTestEmpty() {
        assertNull(PieGeometry.hitTest(emptyList(), 100f, 100f, 100f, 40f, 150f, 100f))
    }

    @Test
    @DisplayName("归一化：最大值映射为 1，全 0 全为 0")
    fun normalize() {
        assertEquals(listOf(0.5f, 1f, 0f), ChartMath.normalize(listOf(50L, 100L, 0L)))
        assertEquals(listOf(0f, 0f), ChartMath.normalize(listOf(0L, 0L)))
        assertEquals(emptyList<Float>(), ChartMath.normalize(emptyList()))
    }

    @Test
    @DisplayName("柱状图布局：柱子宽度与左边界不重叠、不越界")
    fun barLayout() {
        val left = ChartMath.barLeft(0, 4, 400f)
        val width = ChartMath.barWidth(4, 400f)
        assertEquals(400f / 4f * 0.3f / 2f, left, 0.01f)
        assertEquals(400f / 4f * 0.7f, width, 0.01f)
        val second = ChartMath.barLeft(1, 4, 400f)
        assertTrue(second > left + width - 0.01f)
        val last = ChartMath.barLeft(3, 4, 400f)
        assertTrue(last + width <= 400f + 0.01f)
    }

    @Test
    @DisplayName("Y 轴刻度：无数据只给 0")
    fun yTicks() {
        assertEquals(listOf(0L), ChartMath.yTicks(0))
        assertEquals(listOf(0L, 50L, 100L), ChartMath.yTicks(100))
    }

    @Test
    @DisplayName("点位数量与标签一致，缺值补 0")
    fun pointsMatchLabels() {
        val points = ChartMath.points(listOf("a", "b", "c"), listOf(10L, 20L))
        assertEquals(3, points.size)
        assertEquals(0L, points[2].value)
        assertEquals(1f, points[1].ratio)
    }
}
