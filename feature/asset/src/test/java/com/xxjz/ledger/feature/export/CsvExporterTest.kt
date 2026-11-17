package com.xxjz.ledger.feature.export

import com.xxjz.ledger.core.common.DateUtils
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** M8 CSV 导出（AC-75）：BOM、转义、金额、字段勾选、时间范围。 */
class CsvExporterTest {
    private fun row(
        amount: Long = 12_345,
        note: String = "午饭",
        excluded: Boolean = false,
    ) = CsvRow(
        dateTime = DateUtils.toMillis(LocalDate.of(2026, 11, 13), java.time.LocalTime.of(12, 34)),
        typeLabel = "支出",
        category = "餐饮/午餐",
        account = "现金",
        amountCents = amount,
        note = note,
        merchant = "麦当劳",
        tags = "出差,报销",
        excluded = excluded,
    )

    @Test
    @DisplayName("文件以 UTF-8 BOM 开头，行尾是 CRLF")
    fun bomAndEol() {
        val csv = CsvExporter.build(listOf(row()))
        assertTrue(csv.startsWith("\uFEFF"), "缺 BOM 时 Excel 打开中文会乱码")
        assertTrue(csv.contains("\r\n"))
        val lines = csv.removePrefix("\uFEFF").trimEnd('\r', '\n').split("\r\n")
        assertEquals("日期,时间,类型,分类,账户,金额,备注", lines.first())
        assertEquals(2, lines.size)
    }

    @Test
    @DisplayName("金额按元输出两位小数，日期时间取自 dateTime")
    fun amountAndTime() {
        val csv = CsvExporter.build(listOf(row(amount = 123_405)))
        val cells = csv.removePrefix("\uFEFF").split("\r\n")[1].split(",")
        assertEquals("2026-11-13", cells[0])
        assertEquals("12:34", cells[1])
        assertEquals("支出", cells[2])
        assertEquals("1234.05", cells[5])
    }

    @Test
    @DisplayName("含逗号/引号/换行的字段被正确加引号转义（RFC 4180）")
    fun escaping() {
        val csv = CsvExporter.build(listOf(row(note = "打车,加班\n夜里")))
        assertTrue(csv.contains("\"打车,加班\n夜里\""), "含逗号换行必须整体加引号")

        val quotes = CsvExporter.build(listOf(row(note = "他说\"好\"")))
        assertTrue(quotes.contains("\"他说\"\"好\"\"\""), "内部引号要翻倍")

        // 没有特殊字符的字段不加引号，避免文件里到处都是引号
        val plain = CsvExporter.build(listOf(row(note = "普通备注")))
        assertTrue(plain.contains(",普通备注\r\n"))
    }

    @Test
    @DisplayName("只输出勾选的字段，顺序与勾选一致")
    fun selectedFields() {
        val csv = CsvExporter.build(listOf(row()), fields = listOf(CsvField.DATE, CsvField.AMOUNT))
        val lines = csv.removePrefix("\uFEFF").trimEnd('\r', '\n').split("\r\n")
        assertEquals("日期,金额", lines.first())
        assertEquals("2026-11-13,123.45", lines[1])
    }

    @Test
    @DisplayName("金额为 0 或负数也能正确格式化")
    fun amountEdgeCases() {
        assertEquals("0.00", CsvExporter.centsToYuan(0))
        assertEquals("-0.05", CsvExporter.centsToYuan(-5))
        assertEquals("-120.00", CsvExporter.centsToYuan(-12_000))
        assertEquals("99.90", CsvExporter.centsToYuan(9_990))
    }

    @Test
    @DisplayName("「不计入统计」输出是/否")
    fun excludedColumn() {
        val fields = listOf(CsvField.EXCLUDED)
        val yes = CsvExporter.build(listOf(row(excluded = true)), fields).removePrefix("\uFEFF").split("\r\n")
        assertEquals("是", yes[1])
        val no = CsvExporter.build(listOf(row(excluded = false)), fields).removePrefix("\uFEFF").split("\r\n")
        assertEquals("否", no[1])
    }

    @Test
    @DisplayName("空结果只有表头，不会多出空行")
    fun emptyRows() {
        val csv = CsvExporter.build(emptyList())
        assertEquals("\uFEFF日期,时间,类型,分类,账户,金额,备注\r\n", csv)
    }

    @Test
    @DisplayName("本月/本年/全部的时间范围覆盖正确，含末日最后一毫秒")
    fun ranges() {
        val today = LocalDate.of(2026, 11, 13)
        val month = CsvExporter.rangeOf(CsvRange.MONTH, today)
        assertEquals(DateUtils.startOfDayMillis(LocalDate.of(2026, 11, 1)), month.first)
        assertEquals(DateUtils.startOfDayMillis(LocalDate.of(2026, 12, 1)) - 1, month.last)

        val year = CsvExporter.rangeOf(CsvRange.YEAR, today)
        assertEquals(DateUtils.startOfDayMillis(LocalDate.of(2026, 1, 1)), year.first)
        assertEquals(DateUtils.startOfDayMillis(LocalDate.of(2027, 1, 1)) - 1, year.last)

        val all = CsvExporter.rangeOf(CsvRange.ALL, today)
        assertEquals(DateUtils.startOfDayMillis(LocalDate.of(1970, 1, 1)), all.first)
        assertEquals(DateUtils.startOfDayMillis(today.plusDays(1)) - 1, all.last)
    }

    @Test
    @DisplayName("自定义区间反着选也能导出（自动纠正）")
    fun customRangeSwapped() {
        val r =
            CsvExporter.rangeOf(
                CsvRange.CUSTOM,
                LocalDate.of(2026, 11, 13),
                customStart = LocalDate.of(2026, 11, 20),
                customEnd = LocalDate.of(2026, 11, 1),
            )
        assertEquals(DateUtils.startOfDayMillis(LocalDate.of(2026, 11, 1)), r.first)
        assertEquals(DateUtils.startOfDayMillis(LocalDate.of(2026, 11, 21)) - 1, r.last)
    }

    @Test
    @DisplayName("默认文件名带日期时间，扩展名 csv")
    fun fileName() {
        val name = CsvExporter.fileName(DateUtils.toMillis(LocalDate.of(2026, 11, 13), java.time.LocalTime.of(9, 5)))
        assertEquals("一心记账_20261113_0905.csv", name)
    }

    @Test
    @DisplayName("多行数据按时间顺序逐行输出")
    fun multipleRows() {
        val csv = CsvExporter.build(listOf(row(note = "A"), row(note = "B")), fields = listOf(CsvField.NOTE))
        val lines = csv.removePrefix("\uFEFF").trimEnd('\r', '\n').split("\r\n")
        assertEquals(listOf("备注", "A", "B"), lines)
    }
}
