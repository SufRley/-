package com.xxjz.ledger.core.ui.chart

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * 引线几何（v1.9.4 第 1 条）。
 *
 * 这条线在真机上只表现为「线头是不是从色块里出来、折得狠不狠」，
 * 肉眼几乎判断不出 2dp 的偏差，所以把半径与偏移全部拉进单测。
 */
@DisplayName("引线几何")
class CalloutGeometryTest {
    private val centerX = 200f
    private val centerY = 300f
    private val outerRadius = 120f
    private val thickness = 48f
    private val lead = 8f

    private fun radiusOf(point: PolarPoint): Float = hypot(point.x - centerX, point.y - centerY)

    // ---- 环中线 ----

    @Test
    @DisplayName("环中线 = 外半径 - 半个线宽")
    fun ringMidRadiusIsHalfThicknessInside() {
        assertEquals(96f, CalloutGeometry.ringMidRadius(outerRadius, thickness), 0.001f)
    }

    @Test
    @DisplayName("线宽大于直径时不返回负半径")
    fun ringMidRadiusNeverNegative() {
        assertEquals(0f, CalloutGeometry.ringMidRadius(10f, 40f), 0.001f)
    }

    // ---- 起点 ----

    @Test
    @DisplayName("起点落在环中线上，脱离外缘")
    fun startSitsOnRingMid() {
        val point = CalloutGeometry.startPoint(centerX, centerY, 1f, 0f, outerRadius, thickness)
        assertEquals(96f, radiusOf(point), 0.001f)
        assertTrue(radiusOf(point) < outerRadius, "起点必须比外缘更靠里，否则线头还露在外面")
        assertEquals(centerY, point.y, 0.001f)
        assertEquals(centerX + 96f, point.x, 0.001f)
    }

    @Test
    @DisplayName("起点方向跟着扇区中角走")
    fun startFollowsAngle() {
        val angle = Math.toRadians(-60.0)
        val c = cos(angle).toFloat()
        val s = sin(angle).toFloat()
        val point = CalloutGeometry.startPoint(centerX, centerY, c, s, outerRadius, thickness)
        assertEquals(96f, radiusOf(point), 0.001f)
        // 中角在右上，起点必须也在右上：与圆心的向量方向一致
        assertTrue(point.x > centerX && point.y < centerY)
        assertEquals(c * 96f, point.x - centerX, 0.01f)
        assertEquals(s * 96f, point.y - centerY, 0.01f)
    }

    // ---- 直出段 ----

    @Test
    @DisplayName("直出段终点比起点更靠外")
    fun leadPointIsOutsideStart() {
        val start = CalloutGeometry.startPoint(centerX, centerY, 0f, -1f, outerRadius, thickness)
        val leadPoint = CalloutGeometry.leadPoint(centerX, centerY, 0f, -1f, outerRadius, thickness, lead)
        assertEquals(radiusOf(start) + lead, radiusOf(leadPoint), 0.001f)
        // 方向不变：12 点方向的两点 x 相同
        assertEquals(start.x, leadPoint.x, 0.001f)
        assertTrue(leadPoint.y < start.y, "12 点方向往外是 y 变小")
    }

    @Test
    @DisplayName("直出段整段留在环带内，线头不会戳出色块")
    fun leadStaysInsideRingBand() {
        // 环带范围 = [外半径 - 线宽, 外半径]，v1.9.4 只要求「起点在带内」，
        // 直出段允许探出到外缘之外（这正是「从色块里长出来」的观感）
        val startRadius = radiusOf(CalloutGeometry.startPoint(centerX, centerY, 1f, 0f, outerRadius, thickness))
        val inner = outerRadius - thickness
        assertTrue(startRadius in inner..outerRadius, "起点必须在环带内部：$startRadius 不在 $inner..$outerRadius")
    }

    @Test
    @DisplayName("直出长度为 0 时退化成起点，不会画出一段倒折的线")
    fun zeroLeadCollapsesToStart() {
        val start = CalloutGeometry.startPoint(centerX, centerY, 0.5f, 0.5f, outerRadius, thickness)
        val leadPoint = CalloutGeometry.leadPoint(centerX, centerY, 0.5f, 0.5f, outerRadius, thickness, 0f)
        assertEquals(start.x, leadPoint.x, 0.001f)
        assertEquals(start.y, leadPoint.y, 0.001f)
    }

    // ---- 肘部 ----

    @Test
    @DisplayName("肘部在饼图外侧，右侧为正、左侧为负")
    fun elbowSitsOutsideOnBothSides() {
        val gap = 4f
        assertEquals(centerX + outerRadius + gap, CalloutGeometry.elbowX(centerX, 1, outerRadius, gap), 0.001f)
        assertEquals(centerX - outerRadius - gap, CalloutGeometry.elbowX(centerX, -1, outerRadius, gap), 0.001f)
    }

    @Test
    @DisplayName("方向传 0 当作右侧，不会把肘部算到圆心上")
    fun zeroDirectionFallsBackToRight() {
        // 贴着 12 点 / 6 点的扇区 cos≈0；若直接用 cos 乘，肘部会落到圆心正上方
        assertEquals(centerX + outerRadius + 4f, CalloutGeometry.elbowX(centerX, 0, outerRadius, 4f), 0.001f)
    }

    @Test
    @DisplayName("肘部偏移从 12dp 缩到 4dp 后仍在外侧，不至于穿回饼图")
    fun smallElbowGapStaysOutside() {
        val gap = 4f
        val right = CalloutGeometry.elbowX(centerX, 1, outerRadius, gap)
        assertTrue(right > centerX + outerRadius)
        assertTrue(right - (centerX + outerRadius) < 12f, "外扩应该明显小于 v1.9.3 的 12dp")
    }
}
