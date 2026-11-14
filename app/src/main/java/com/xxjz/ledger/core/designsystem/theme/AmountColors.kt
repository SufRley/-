package com.xxjz.ledger.core.designsystem.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
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

/**
 * 投资盈亏配色（v1.8.0，PRD：红涨绿跌）。
 *
 * 与收支配色（[AmountColors.income]/[AmountColors.expense]）分开是有意为之：
 * 盈亏的语义是「赚了/亏了」，国内习惯赚用红、亏用绿，与收入绿/支出红正好相反；
 * 收支配色还支持用户反转，盈亏不受影响，避免两个开关互相打结。
 */
@Composable
fun profitColor(profitCents: Long): Color =
    when {
        profitCents > 0 -> ExpenseRedLight
        profitCents < 0 -> IncomeGreenLight
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
