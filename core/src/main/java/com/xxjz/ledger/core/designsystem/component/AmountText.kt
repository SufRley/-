package com.xxjz.ledger.core.designsystem.component

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import com.xxjz.ledger.core.common.MoneyFormatter
import com.xxjz.ledger.core.designsystem.theme.LocalAmountColors

/** 收支方向。 */
enum class AmountDirection { INCOME, EXPENSE, NEUTRAL }

/**
 * 金额文字：统一从 LocalAmountColors 取色，符号固定展示。
 * @param cents 金额（分），始终传正数
 */
@Composable
fun AmountText(
    cents: Long,
    direction: AmountDirection,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    showSymbol: Boolean = true,
    signed: Boolean = true,
) {
    val colors = LocalAmountColors.current
    val color: Color =
        when (direction) {
            AmountDirection.INCOME -> colors.income
            AmountDirection.EXPENSE -> colors.expense
            AmountDirection.NEUTRAL -> Color.Unspecified
        }
    val text =
        when {
            !signed -> (if (showSymbol) "¥" else "") + MoneyFormatter.centsToYuan(cents)
            direction == AmountDirection.INCOME -> MoneyFormatter.centsToSigned(cents, positive = true)
            direction == AmountDirection.EXPENSE -> MoneyFormatter.centsToSigned(cents, positive = false)
            else -> MoneyFormatter.centsToYuan(cents, showSymbol)
        }
    Text(text = text, modifier = modifier, color = color, style = style.copy(fontWeight = FontWeight.Medium))
}
