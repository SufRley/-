package com.xxjz.ledger.core.ui.chart

import kotlin.math.atan2
import kotlin.math.hypot

/** 饼图/环图的一瓣。 */
data class PieSlice(
    val label: String,
    val value: Long,
    val color: Long,
)

/** 扇区角度区间（角度制，0° 指向 3 点方向，顺时针为正）。 */
data class SliceArc(
    val index: Int,
    val startAngle: Float,
    val sweepAngle: Float,
) {
    val endAngle: Float get() = startAngle + sweepAngle
    val midAngle: Float get() = startAngle + sweepAngle / 2f
}

/**
 * 饼图几何计算（纯函数，供 Canvas 绘制与点击命中共用）。
 *
 * 起始角固定 -90°（12 点方向），顺时针铺开（DEV_PLAN M5 技术点）。
 */
object PieGeometry {
    const val START_ANGLE: Float = -90f

    /** 按数值比例切分扇区；总值为 0 返回空列表（不画空圈）。 */
    fun arcs(values: List<Long>): List<SliceArc> {
        val total = values.sum()
        if (total <= 0) return emptyList()
        var cursor = START_ANGLE
        return values.mapIndexed { index, value ->
            val sweep = (value.toDouble() / total * 360.0).toFloat()
            val arc = SliceArc(index = index, startAngle = cursor, sweepAngle = sweep)
            cursor += sweep
            arc
        }
    }

    /**
     * 点击命中检测：算出点击点相对圆心的角度，落在哪个扇区区间就返回哪个下标。
     *
     * 比逐个 Path hit-test 简单可靠；点击落在环外或圆心空洞里返回 null。
     */
    fun hitTest(
        arcs: List<SliceArc>,
        centerX: Float,
        centerY: Float,
        radius: Float,
        innerRadius: Float,
        tapX: Float,
        tapY: Float,
    ): Int? {
        val dx = tapX - centerX
        val dy = tapY - centerY
        val dist = hypot(dx, dy)
        if (dist > radius || dist < innerRadius) return null
        // atan2 的 0° 在 3 点方向，顺时针为正，与 Canvas 一致
        var angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
        if (angle < START_ANGLE) angle += 360f
        return arcs.firstOrNull { angle >= it.startAngle && angle < it.endAngle }?.index
    }

    /** 环图中心的百分比文案（取最大一瓣）。 */
    fun centerLabel(arcs: List<SliceArc>): String =
        arcs.maxByOrNull { it.sweepAngle }?.let { "${(it.sweepAngle / 360f * 100).toInt()}%" } ?: "—"
}

/** 一段中角方向上的极坐标点（[cos]/[sin] 由扇区中角算好，多次复用）。 */
data class PolarPoint(
    val x: Float,
    val y: Float,
)

/**
 * 引线几何（v1.9.4 第 1 条）。
 *
 * 拆出来是为了能脱离 Canvas 单测：这条线涉及的半径、偏移全是纯算术，
 * 一旦算错在真机上只表现为「线头戳出色块 2dp」，肉眼很难判断对不对。
 *
 * 画法：环中线 → 沿扇区方向直出 [LEAD] → 折向水平 → 贴到文字边缘。
 */
object CalloutGeometry {
    /**
     * 环中线半径。
     *
     * 描边是**居中**画的（`drawArc` 的 `Stroke` 默认），所以外半径减去半个线宽，
     * 落点正好在环带正中间；再往外 [LEAD] 也还在环带里（环带半宽 = 线宽/2 = 12dp）。
     */
    fun ringMidRadius(
        outerRadius: Float,
        thickness: Float,
    ): Float = (outerRadius - thickness / 2f).coerceAtLeast(0f)

    /**
     * 引线起点：环中线。
     *
     * 故意**不跟随选中态**——选中段的描边是 1.25 倍宽，中线会再往里 1.5dp，
     * 但让起点跟着点击一起微跳反而更显眼（PRD 2.3）。
     */
    fun startPoint(
        centerX: Float,
        centerY: Float,
        cos: Float,
        sin: Float,
        outerRadius: Float,
        thickness: Float,
    ): PolarPoint = pointAt(centerX, centerY, cos, sin, ringMidRadius(outerRadius, thickness))

    /** 直出段的终点：环中线再沿同一方向推 [lead]。 */
    fun leadPoint(
        centerX: Float,
        centerY: Float,
        cos: Float,
        sin: Float,
        outerRadius: Float,
        thickness: Float,
        lead: Float,
    ): PolarPoint = pointAt(centerX, centerY, cos, sin, ringMidRadius(outerRadius, thickness) + lead)

    /**
     * 肘部的横坐标：饼图外侧 [gap] 处。
     *
     * 用**方向符号**（±1）而不是 cos 本身：贴着 12 点 / 6 点方向的扇区 cos ≈ 0，
     * 乘出来肘部会贴在圆心正上方，引线直接从饼图里穿过去。
     */
    fun elbowX(
        centerX: Float,
        direction: Int,
        outerRadius: Float,
        gap: Float,
    ): Float = centerX + direction.sign() * (outerRadius + gap)

    /** 同 [elbowX] 的纵坐标基准：肘部高度就是标签基线高度。 */
    private fun Int.sign(): Int = if (this < 0) -1 else 1

    private fun pointAt(
        centerX: Float,
        centerY: Float,
        cos: Float,
        sin: Float,
        radius: Float,
    ): PolarPoint = PolarPoint(x = centerX + cos * radius, y = centerY + sin * radius)
}

/** 柱状图/折线图的一个点（已算好比例，Canvas 直接用）。 */
data class ChartPoint(
    val label: String,
    /** 0f~1f，相对最大值的比例。 */
    val ratio: Float,
    val value: Long,
)

/**
 * 折线与柱状图的数据归一化。
 *
 * 年视图 12 个月 × 每天的数据量不小，比例在 usecase/工具层算好，Canvas 只负责画（DEV_PLAN M5 技术点）。
 */
object ChartMath {
    fun normalize(values: List<Long>): List<Float> {
        val max = values.maxOrNull() ?: 0L
        if (max <= 0L) return values.map { 0f }
        return values.map { (it.toDouble() / max).toFloat() }
    }

    fun points(
        labels: List<String>,
        values: List<Long>,
    ): List<ChartPoint> {
        // 标签比数据长时补 0，保证点位与 X 轴一一对应
        val padded = labels.indices.map { values.getOrElse(it) { 0L } }
        val ratios = normalize(padded)
        return labels.mapIndexed { index, label ->
            ChartPoint(label = label, ratio = ratios[index], value = padded[index])
        }
    }

    /** Y 轴刻度（0、1/2、最大值），金额格式化交给调用方。 */
    fun yTicks(maxValue: Long): List<Long> = if (maxValue <= 0L) listOf(0L) else listOf(0L, maxValue / 2, maxValue)

    /** 柱子在给定宽度内的左边界。 */
    fun barLeft(
        index: Int,
        count: Int,
        width: Float,
        gapRatio: Float = 0.3f,
    ): Float {
        if (count <= 0) return 0f
        val slot = width / count
        val gap = slot * gapRatio
        return index * slot + gap / 2f
    }

    fun barWidth(
        count: Int,
        width: Float,
        gapRatio: Float = 0.3f,
    ): Float {
        if (count <= 0) return 0f
        val slot = width / count
        return slot * (1f - gapRatio)
    }
}
