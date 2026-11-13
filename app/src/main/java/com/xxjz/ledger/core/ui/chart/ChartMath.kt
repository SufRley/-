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
