package com.xxjz.ledger.feature.editor

import com.xxjz.ledger.core.common.DateUtils
import com.xxjz.ledger.domain.model.Tag
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * 状态胶囊的文案与「只改一半时间」的合并逻辑（PRD v1.9.3 第 6.8 条）。
 *
 * 这几条纯函数看着不起眼，但它们决定胶囊宽度**稳不稳定**：
 * 文案一旦在「今天」「10/12」「2025/1/5」之间横跳，整行胶囊就会左右抖动，
 * 手指按下去常常点偏。所以宁可多写几条断言，把每种日期边界都钉死。
 */
@DisplayName("状态胶囊文案")
class EditorChipsTextTest {
    @Test
    fun `今天昨天前天用相对说法并带周几`() {
        val today = DateUtils.today()
        val text = dateChipText(DateUtils.toMillis(today, LocalTime.of(20, 2)))
        assertTrue(text.startsWith("今天 "), "实际是「$text」")
        assertTrue(text.endsWith("周" + "一二三四五六日"[today.dayOfWeek.value - 1]))

        assertTrue(dateChipText(DateUtils.toMillis(today.minusDays(1), LocalTime.NOON)).startsWith("昨天 "))
        assertTrue(dateChipText(DateUtils.toMillis(today.minusDays(2), LocalTime.NOON)).startsWith("前天 "))
    }

    @Test
    fun `同年只写月日，跨年才补年份`() {
        val today = DateUtils.today()
        // 挑一个肯定不是今天/昨天/前天的日子：本月的 1 号往前推 40 天
        val sameYear = today.minusDays(40)
        val text = dateChipText(DateUtils.toMillis(sameYear, LocalTime.NOON))
        if (sameYear.year == today.year) {
            assertTrue(text.startsWith("${sameYear.monthValue}/${sameYear.dayOfMonth} "), "实际是「$text」")
        }
        val lastYear = LocalDate.of(today.year - 1, 1, 5)
        assertTrue(dateChipText(DateUtils.toMillis(lastYear, LocalTime.NOON)).startsWith("${lastYear.year}/1/5"))
    }

    @Test
    fun `时间补零对齐`() {
        val day = LocalDate.of(2026, 3, 8)
        assertEquals("20:02", timeChipText(DateUtils.toMillis(day, LocalTime.of(20, 2))))
        assertEquals("09:30", timeChipText(DateUtils.toMillis(day, LocalTime.of(9, 30))))
        assertEquals("00:00", timeChipText(DateUtils.toMillis(day, LocalTime.MIDNIGHT)))
    }

    @Test
    fun `标签胶囊没选是空态，多选只报第一个加数量`() {
        val tags = listOf(Tag(id = "t1", name = "出差"), Tag(id = "t2", name = "报销"), Tag(id = "t3", name = "家庭"))
        assertEquals("无标签", tagChipText(tags, emptySet()))
        assertEquals("出差", tagChipText(tags, setOf("t1")))
        assertEquals("出差 +2", tagChipText(tags, setOf("t1", "t2", "t3")))
        // 标签被删掉但 txn_tag 里还留着 id：不能显示成空白胶囊
        assertEquals("已选 1 个标签", tagChipText(tags, setOf("gone")))
    }

    @Test
    fun `分期与退款胶囊文案`() {
        assertEquals("无分期", installmentChipText(null))
        assertEquals("分 3 期", installmentChipText(3))
        assertEquals("无退款", refundChipText(0))
        assertEquals("已退 ¥50.00", refundChipText(50_00))
    }

    @Test
    fun `改日期不动原来的时分`() {
        val original = DateUtils.toMillis(LocalDate.of(2026, 3, 8), LocalTime.of(20, 2))
        val newDate = DateUtils.toMillis(LocalDate.of(2026, 3, 1), LocalTime.of(8, 0))
        val merged = DateUtils.toDateTime(keepTimeOf(original, newDate))
        assertEquals(LocalDate.of(2026, 3, 1), merged.toLocalDate())
        // 「点了昨天」不该顺手把 20:02 变成 00:00
        assertEquals(LocalTime.of(20, 2), merged.toLocalTime())
    }

    @Test
    fun `改时间不动原来的日期`() {
        val original = DateUtils.toMillis(LocalDate.of(2026, 3, 8), LocalTime.of(20, 2))
        val merged = DateUtils.toDateTime(withTimeOf(original, LocalTime.of(9, 15)))
        assertEquals(LocalDate.of(2026, 3, 8), merged.toLocalDate())
        assertEquals(LocalTime.of(9, 15), merged.toLocalTime())
    }
}
