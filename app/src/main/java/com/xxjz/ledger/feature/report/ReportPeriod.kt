package com.xxjz.ledger.feature.report

/**
 * 报表时间粒度（M5-T3）。
 *
 * `range(today)` 给出该粒度的起止毫秒，`previousLabel` 用于标题展示。
 */
enum class ReportPeriod(val label: String) {
    WEEK("周"),
    MONTH("月"),
    YEAR("年"),
}
