package com.xxjz.ledger.core.common

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs

/**
 * 金额工具：内部一律以「分」(Long) 存储，展示时转「元」。
 *
 * 9 位上限约定：金额的「元」部分最多 9 位整数，即 |分| <= 999_999_999_99（11 位分）。
 */
object MoneyFormatter {
    private const val TAG = "MoneyFormatter"

    const val MAX_YUAN_DIGITS = 9
    const val MAX_CENTS: Long = 99_999_999_999L

    /** 「分」转「元」字符串，带千分位，例如 1234567 -> "12,345.67"。 */
    fun centsToYuan(
        cents: Long,
        showSymbol: Boolean = false,
        symbol: String = "¥",
    ): String {
        val negative = cents < 0
        val abs = abs(cents)
        val yuan = abs / 100
        val fen = abs % 100
        val yuanStr = groupDigits(yuan.toString())
        val frac = if (fen == 0L) "00" else fen.toString().padStart(2, '0')
        val body = "$yuanStr.$frac"
        val prefix = if (negative) "-" else ""
        return if (showSymbol) "$prefix$symbol$body" else "$prefix$body"
    }

    /** 带符号展示：支出显示为 "-12.00"，收入显示为 "+12.00"。 */
    fun centsToSigned(
        cents: Long,
        positive: Boolean,
        symbol: String = "¥",
    ): String {
        val sign = if (positive) "+" else "-"
        return "$sign$symbol${centsToYuan(abs(cents))}"
    }

    /** 输入框中的原始数字（不含千分位），例如 1234567 -> "12345.67"，整元时不带小数补零。 */
    fun centsToPlain(cents: Long): String {
        val negative = cents < 0
        val abs = abs(cents)
        val yuan = abs / 100
        val fen = abs % 100
        val frac = if (fen == 0L) "" else "." + fen.toString().padStart(2, '0').trimEnd('0')
        return (if (negative) "-" else "") + yuan.toString() + frac
    }

    /**
     * 「元」字符串解析为「分」，按四舍五入到分（0.005 -> 1 分）。
     * 非法输入返回 null。
     */
    fun yuanToCents(text: String): Long? {
        val t = text.trim().replace(",", "")
        if (isBlankAmount(t)) return null
        return try {
            val bd = BigDecimal(t)
            bd.setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact()
        } catch (e: NumberFormatException) {
            // 用 BigDecimal 解析失败只会抛 NumberFormatException（如 "1.2.3"）；
            // 溢出/超出精度由 longValueExact 抛 ArithmeticException，也一并归为「非法输入」
            Logger.d(TAG, "yuanToCents 非法输入: $t", e)
            null
        } catch (e: ArithmeticException) {
            Logger.d(TAG, "yuanToCents 超出可表示范围: $t", e)
            null
        }
    }

    /** 只有符号或小数点、以及空串，都算没输入。 */
    private fun isBlankAmount(t: String): Boolean = t.isEmpty() || t == "-" || t == "." || t == "-."

    /** 是否超出 9 位上限。 */
    fun exceedsLimit(cents: Long): Boolean = abs(cents) > MAX_CENTS

    /** 千分位分组。 */
    fun groupDigits(raw: String): String {
        if (raw.length <= 3) return raw
        val sb = StringBuilder()
        val first = raw.length % 3
        if (first > 0) sb.append(raw, 0, first)
        var i = first
        while (i < raw.length) {
            if (sb.isNotEmpty()) sb.append(',')
            sb.append(raw, i, i + 3)
            i += 3
        }
        return sb.toString()
    }
}
