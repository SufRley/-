package com.xxjz.ledger.domain.model

/**
 * 小组件的取数兜底。
 *
 * 桌面组件崩一次，系统会一直显示「加载失败」并可能把组件从桌面移除，
 * 所以任何异常都降级成空卡片——宁可显示「还没记账」，也不能让桌面上留一块白板。
 *
 * 抽成独立函数是为了能在纯 JVM 单测里验证这条降级路径，不必起 Glance 的运行时。
 */
object WidgetSummaryLoader {
    suspend fun load(block: suspend () -> WidgetSummary): WidgetSummary = runCatching { block() }.getOrElse { WidgetSummary() }
}
