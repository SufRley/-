package com.xxjz.ledger.core.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val LightColors =
    lightColorScheme(
        primary = GreenPrimary,
        onPrimary = Color.White,
        primaryContainer = GreenContainerLight,
        onPrimaryContainer = Color(0xFF0B2F1F),
        secondary = Color(0xFF52655A),
        background = NeutralSurfaceLight,
        surface = Color.White,
        surfaceVariant = Color(0xFFEDF1EE),
        onSurface = Color(0xFF171C19),
        onSurfaceVariant = Color(0xFF5C6760),
        error = ExpenseRedLight,
        outline = Color(0xFFB9C3BC),
        outlineVariant = Color(0xFFDCE3DE),
    )

private val DarkColors =
    darkColorScheme(
        primary = GreenPrimaryDark,
        onPrimary = Color(0xFF07301F),
        primaryContainer = GreenContainerDark,
        onPrimaryContainer = Color(0xFFC7F2DA),
        secondary = Color(0xFFB6C7BB),
        background = NeutralSurfaceDark,
        surface = Color(0xFF191D1A),
        surfaceVariant = Color(0xFF232A26),
        onSurface = Color(0xFFE4E9E5),
        onSurfaceVariant = Color(0xFFA8B2AB),
        error = ExpenseRedDark,
        outline = Color(0xFF3D4A42),
        outlineVariant = Color(0xFF2B342E),
    )

private val XxjzShapes =
    Shapes(
        extraSmall = RoundedCornerShape(6.dp),
        small = RoundedCornerShape(10.dp),
        medium = RoundedCornerShape(14.dp),
        large = RoundedCornerShape(20.dp),
        extraLarge = RoundedCornerShape(28.dp),
    )

/** 主题模式：浅色 / 深色 / 跟随系统。 */
enum class ThemeMode { LIGHT, DARK, SYSTEM }

/**
 * 按模式解析出对应的收支配色。
 * 未反转：收入=绿、支出=红；反转后：收入=红、支出=绿（AC-41）。
 */
fun amountColorsFor(
    dark: Boolean,
    reversed: Boolean,
): AmountColors {
    val normalIncome = if (dark) IncomeGreenDark else IncomeGreenLight
    val normalExpense = if (dark) ExpenseRedDark else ExpenseRedLight
    return AmountColors(
        income = if (reversed) normalExpense else normalIncome,
        expense = if (reversed) normalIncome else normalExpense,
        warning = WarningAmber,
        overBudget = OverBudgetRed,
        reversed = reversed,
        transfer = if (dark) TransferBlueDark else TransferBlueLight,
        debt = if (dark) DebtPurpleDark else DebtPurpleLight,
    )
}

@Composable
fun XxjzTheme(
    mode: ThemeMode = ThemeMode.SYSTEM,
    amountColorsReversed: Boolean = false,
    content: @Composable () -> Unit,
) {
    val dark =
        when (mode) {
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
            ThemeMode.SYSTEM -> isSystemInDarkTheme()
        }
    val colors = if (dark) DarkColors else LightColors
    CompositionLocalProvider(LocalAmountColors provides amountColorsFor(dark, amountColorsReversed)) {
        MaterialTheme(
            colorScheme = colors,
            typography = XxjzTypography,
            shapes = XxjzShapes,
            content = content,
        )
    }
}
