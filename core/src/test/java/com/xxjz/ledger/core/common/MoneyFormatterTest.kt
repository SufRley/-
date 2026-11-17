package com.xxjz.ledger.core.common

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("MoneyFormatter 金额工具")
class MoneyFormatterTest {
    @Test
    fun `分转元带千分位`() {
        assertEquals("12,345.67", MoneyFormatter.centsToYuan(1_234_567))
        assertEquals("0.00", MoneyFormatter.centsToYuan(0))
        assertEquals("1.05", MoneyFormatter.centsToYuan(105))
        assertEquals("1,000,000.00", MoneyFormatter.centsToYuan(100_000_000))
    }

    @Test
    fun `负数金额`() {
        assertEquals("-12,345.67", MoneyFormatter.centsToYuan(-1_234_567))
        assertEquals("-¥0.50", MoneyFormatter.centsToYuan(-50, showSymbol = true))
    }

    @Test
    fun `元解析为分`() {
        assertEquals(1250L, MoneyFormatter.yuanToCents("12.5"))
        assertEquals(1250L, MoneyFormatter.yuanToCents("12.50"))
        assertEquals(1200L, MoneyFormatter.yuanToCents("12"))
        assertEquals(1234567L, MoneyFormatter.yuanToCents("12,345.67"))
        assertEquals(-100L, MoneyFormatter.yuanToCents("-1"))
    }

    @Test
    fun `四舍五入到分`() {
        assertEquals(1L, MoneyFormatter.yuanToCents("0.005"))
        assertEquals(0L, MoneyFormatter.yuanToCents("0.004"))
        assertEquals(3L, MoneyFormatter.yuanToCents("0.025"))
        assertEquals(-1L, MoneyFormatter.yuanToCents("-0.005"))
    }

    @Test
    fun `往返转换一致`() {
        val cents = 987_654_321L
        assertEquals(cents, MoneyFormatter.yuanToCents(MoneyFormatter.centsToPlain(cents)))
        assertEquals("0", MoneyFormatter.centsToPlain(0))
        assertEquals("9876543.21", MoneyFormatter.centsToPlain(cents))
        assertEquals("12.5", MoneyFormatter.centsToPlain(1250))
    }

    @Test
    fun `非法输入返回 null`() {
        assertNull(MoneyFormatter.yuanToCents(""))
        assertNull(MoneyFormatter.yuanToCents("abc"))
        assertNull(MoneyFormatter.yuanToCents("-"))
        assertNull(MoneyFormatter.yuanToCents("."))
        assertNull(MoneyFormatter.yuanToCents("1.2.3"))
    }

    @Test
    fun `9位上限`() {
        assertFalse(MoneyFormatter.exceedsLimit(MoneyFormatter.MAX_CENTS))
        assertTrue(MoneyFormatter.exceedsLimit(MoneyFormatter.MAX_CENTS + 1))
        assertTrue(MoneyFormatter.exceedsLimit(-MoneyFormatter.MAX_CENTS - 1))
        assertFalse(MoneyFormatter.exceedsLimit(0))
        assertEquals("999,999,999.99", MoneyFormatter.centsToYuan(MoneyFormatter.MAX_CENTS))
    }

    @Test
    fun `带符号展示`() {
        assertEquals("+¥12.50", MoneyFormatter.centsToSigned(1250, positive = true))
        assertEquals("-¥12.50", MoneyFormatter.centsToSigned(1250, positive = false))
    }
}
