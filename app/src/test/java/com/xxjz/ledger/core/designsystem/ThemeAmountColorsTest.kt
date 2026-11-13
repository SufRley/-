package com.xxjz.ledger.core.designsystem

import com.xxjz.ledger.core.designsystem.theme.ExpenseRedLight
import com.xxjz.ledger.core.designsystem.theme.IncomeGreenLight
import com.xxjz.ledger.core.designsystem.theme.amountColorsFor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("收支配色（AC-81 / AC-41）")
class ThemeAmountColorsTest {
    @Test
    fun `默认收入绿支出红`() {
        val c = amountColorsFor(dark = false, reversed = false)
        assertEquals(IncomeGreenLight, c.income)
        assertEquals(ExpenseRedLight, c.expense)
        assertFalse(c.reversed)
    }

    @Test
    fun `反转后收入红支出绿`() {
        val c = amountColorsFor(dark = false, reversed = true)
        assertEquals(ExpenseRedLight, c.income)
        assertEquals(IncomeGreenLight, c.expense)
        assertTrue(c.reversed)
    }

    @Test
    fun `深色模式使用深色语义色`() {
        val light = amountColorsFor(dark = false, reversed = false)
        val dark = amountColorsFor(dark = true, reversed = false)
        assertTrue(light.income != dark.income)
        assertTrue(light.expense != dark.expense)
    }
}
