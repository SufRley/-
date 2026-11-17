package com.xxjz.ledger.feature.export

import com.xxjz.ledger.core.common.DateUtils
import java.time.Instant
import java.time.ZoneId

/** CSV 可导出字段（P24 的勾选项）。 */
enum class CsvField(
    val label: String,
) {
    DATE("日期"),
    TIME("时间"),
    TYPE("类型"),
    CATEGORY("分类"),
    ACCOUNT("账户"),
    AMOUNT("金额"),
    NOTE("备注"),
    MERCHANT("商家"),
    TAGS("标签"),
    EXCLUDED("不计入统计"),
}

/** 一行待导出的扁平数据；由 ViewModel 从 Txn + 分类/账户名组装，导出器本身不碰数据库。 */
data class CsvRow(
    val dateTime: Long,
    val typeLabel: String,
    val category: String = "",
    val account: String = "",
    val amountCents: Long = 0,
    val note: String = "",
    val merchant: String = "",
    val tags: String = "",
    val excluded: Boolean = false,
)

/**
 * CSV 生成（AC-75）。
 *
 * 两个必须遵守的细节：
 * 1. **文件头写入 `\uFEFF`**，否则 Excel 按 GBK 猜编码，中文全变乱码；
 * 2. 含逗号 / 引号 / 换行的字段用双引号包裹，内部引号翻倍（RFC 4180）。
 *
 * 行尾用 `\r\n`：Windows 版 Excel 对纯 `\n` 的兼容性更差。
 */
object CsvExporter {
    /** Excel 识别 UTF-8 的 BOM。 */
    const val BOM = "\uFEFF"

    const val EOL = "\r\n"

    /** 默认字段组合：常用的都在，标签这种可选的默认不带。 */
    val DEFAULT_FIELDS: List<CsvField> =
        listOf(
            CsvField.DATE,
            CsvField.TIME,
            CsvField.TYPE,
            CsvField.CATEGORY,
            CsvField.ACCOUNT,
            CsvField.AMOUNT,
            CsvField.NOTE,
        )

    fun build(
        rows: List<CsvRow>,
        fields: List<CsvField> = DEFAULT_FIELDS,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        if (fields.isEmpty()) return BOM + EOL
        val sb = StringBuilder(BOM)
        sb.append(fields.joinToString(",") { escape(it.label) }).append(EOL)
        rows.forEach { row ->
            sb.append(fields.joinToString(",") { escape(cell(row, it, zone)) }).append(EOL)
        }
        return sb.toString()
    }

    /** 「金额」输出成元（两位小数），其他字段原样。 */
    internal fun cell(
        row: CsvRow,
        field: CsvField,
        zone: ZoneId,
    ): String {
        val time = Instant.ofEpochMilli(row.dateTime).atZone(zone)
        return when (field) {
            CsvField.DATE -> "%04d-%02d-%02d".format(time.year, time.monthValue, time.dayOfMonth)
            CsvField.TIME -> "%02d:%02d".format(time.hour, time.minute)
            CsvField.TYPE -> row.typeLabel
            CsvField.CATEGORY -> row.category
            CsvField.ACCOUNT -> row.account
            CsvField.AMOUNT -> centsToYuan(row.amountCents)
            CsvField.NOTE -> row.note
            CsvField.MERCHANT -> row.merchant
            CsvField.TAGS -> row.tags
            CsvField.EXCLUDED -> if (row.excluded) "是" else "否"
        }
    }

    internal fun centsToYuan(cents: Long): String {
        val negative = cents < 0
        val abs = kotlin.math.abs(cents)
        val text = "${abs / 100}.${(abs % 100).toString().padStart(2, '0')}"
        return if (negative) "-$text" else text
    }

    /** RFC 4180 转义。 */
    internal fun escape(value: String): String {
        val needsQuote = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        if (!needsQuote) return value
        return "\"" + value.replace("\"", "\"\"") + "\""
    }

    /** 文件名建议：`一心记账_20261113_2145.csv`。 */
    fun fileName(
        now: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val t = Instant.ofEpochMilli(now).atZone(zone)
        return "一心记账_%04d%02d%02d_%02d%02d.csv".format(t.year, t.monthValue, t.dayOfMonth, t.hour, t.minute)
    }

    /**
     * 把「本月/本年/全部/自定义」翻译成时间区间（毫秒，左闭右开）。
     *
     * @param customStart 自定义起始日（`yyyyMMdd`），仅 [CsvRange.CUSTOM] 使用
     */
    fun rangeOf(
        range: CsvRange,
        today: java.time.LocalDate,
        customStart: java.time.LocalDate? = null,
        customEnd: java.time.LocalDate? = null,
    ): LongRange {
        val start =
            when (range) {
                CsvRange.MONTH -> today.withDayOfMonth(1)
                CsvRange.YEAR -> today.withDayOfYear(1)
                CsvRange.ALL -> java.time.LocalDate.of(1970, 1, 1)
                CsvRange.CUSTOM -> customStart ?: today
            }
        val end =
            when (range) {
                CsvRange.MONTH -> today.withDayOfMonth(1).plusMonths(1).minusDays(1)
                CsvRange.YEAR -> today.withDayOfYear(1).plusYears(1).minusDays(1)
                CsvRange.ALL -> today
                CsvRange.CUSTOM -> customEnd ?: today
            }
        // 自定义区间反了就自动纠正，避免导出空文件（先比日期再算毫秒）
        val lo = if (start <= end) start else end
        val hi = if (start <= end) end else start
        val from = DateUtils.startOfDayMillis(lo)
        val to = DateUtils.startOfDayMillis(hi.plusDays(1)) - 1
        return from..to
    }
}

/** 导出时间范围。 */
enum class CsvRange(
    val label: String,
) {
    MONTH("本月"),
    YEAR("本年"),
    ALL("全部"),
    CUSTOM("自定义"),
}
