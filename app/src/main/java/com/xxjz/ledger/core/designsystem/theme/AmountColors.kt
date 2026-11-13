package com.xxjz.ledger.core.designsystem.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * 收支语义配色。AC-41 要求「收入/支出颜色反转」可以一键全局生效，
 * 所有金额组件必须从这里取色，禁止写死 Color.Red/Color.Green。
 */
data class AmountColors(
    val income: Color,
    val expense: Color,
    val warning: Color,
    val overBudget: Color,
    /** true 表示已开启配色反转（收入=红，支出=绿）。 */
    val reversed: Boolean = false,
)

val LocalAmountColors =
    staticCompositionLocalOf {
        AmountColors(
            income = IncomeGreenLight,
            expense = ExpenseRedLight,
            warning = WarningAmber,
            overBudget = OverBudgetRed,
        )
    }
