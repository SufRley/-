package com.xxjz.ledger.feature.editor

import com.xxjz.ledger.core.common.MoneyFormatter
import java.math.BigDecimal
import java.math.RoundingMode

/** 四则运算。precedence 用于双栈求值时比较优先级。 */
enum class CalcOp(
    val symbol: String,
    val precedence: Int,
) {
    ADD("+", 1),
    SUB("-", 1),
    MUL("×", 2),
    DIV("÷", 2),
}

/**
 * 计算器状态（不可变）。
 *
 * 采用「双栈」模型：values 存已确定的操作数，ops 存待处理运算符，buffer 是当前正在输入的数字。
 * 按下运算符时，只要栈顶运算符优先级不低于新运算符就先折叠，因此 `12.5 + 8 × 2` 能先算乘法。
 */
data class CalcState(
    val values: List<BigDecimal> = emptyList(),
    val ops: List<CalcOp> = emptyList(),
    val buffer: String = "0",
    val error: String? = null,
    /** 已经按过 `=`，此时再按 `=` 表示保存（AC-03）。 */
    val evaluated: Boolean = false,
) {
    /** 主展示区文本：错误优先，否则是当前输入。 */
    val display: String get() = error ?: buffer

    /** 是否已求值，可用于「保存」按钮态。 */
    val canSave: Boolean get() = error == null && evaluated

    /** 表达式回显，如 `12.5 + 8 ×`。 */
    val expressionText: String get() = CalcEngine.expressionOf(this)
}

/**
 * 记账面板计算器引擎。
 *
 * 设计约束（DEV_PLAN M2 关键技术点）：不用 eval、不写表达式解析树，用双栈直接求值；
 * 所有中间结果保留 2 位小数（四舍五入），并与「分」精度保持一致。
 */
object CalcEngine {
    /** 整数部分最大位数，9 位对应 999,999,999.99 元。 */
    const val MAX_INT_DIGITS = 9

    /** 小数位固定 2 位（记账精度）。 */
    const val MAX_DECIMALS = 2

    private val MAX_ABS: BigDecimal = BigDecimal("999999999.99")

    fun initial(): CalcState = CalcState()

    /** 输入数字。超长或已求值后自动开新表达式。 */
    fun digit(
        state: CalcState,
        d: Char,
    ): CalcState {
        var s = state
        if (s.error != null || s.evaluated) s = CalcState()
        val buf = s.buffer
        val next =
            when {
                buf == "0" -> d.toString()
                buf.contains('.') && decimalsOf(buf) >= MAX_DECIMALS -> return s
                !buf.contains('.') && buf.length >= MAX_INT_DIGITS -> return s
                else -> buf + d
            }
        return s.copy(buffer = next, evaluated = false)
    }

    /** 输入小数点：去重，自动补 0。 */
    fun dot(state: CalcState): CalcState {
        var s = state
        if (s.error != null || s.evaluated) s = CalcState()
        if (s.buffer.contains('.')) return s
        return s.copy(buffer = s.buffer + ".", evaluated = false)
    }

    /** 退格：删到空归零；错误/已求值状态直接清空。 */
    fun backspace(state: CalcState): CalcState {
        if (state.error != null || state.evaluated) return CalcState()
        if (state.buffer.isEmpty() || state.buffer == "0") return state.copy(buffer = "0")
        val next = state.buffer.dropLast(1)
        return state.copy(buffer = if (next.isEmpty() || next == "-") "0" else next)
    }

    fun clear(state: CalcState = CalcState()): CalcState = CalcState()

    /** 输入运算符：按优先级折叠栈顶，再把结果与新运算符入栈。 */
    fun op(
        state: CalcState,
        op: CalcOp,
    ): CalcState {
        if (state.error != null) return CalcState()
        val operand = state.buffer.toBigDecimalOrNull() ?: BigDecimal.ZERO
        if (state.evaluated) {
            return CalcState(values = listOf(operand), ops = listOf(op), buffer = "0")
        }
        val values = state.values.toMutableList()
        val ops = state.ops.toMutableList()
        var acc = operand
        while (ops.isNotEmpty() && ops.last().precedence >= op.precedence) {
            val left = values.removeAt(values.lastIndex)
            val pending = ops.removeAt(ops.lastIndex)
            acc = apply(left, pending, acc) ?: return errorState(pending, acc)
        }
        values.add(acc)
        ops.add(op)
        return CalcState(values = values, ops = ops, buffer = "0")
    }

    /** 求值。栈空时只把当前输入固化为结果。 */
    fun equals(state: CalcState): CalcState {
        if (state.error != null) return CalcState()
        if (state.evaluated) return state
        val values = state.values.toMutableList()
        val ops = state.ops.toMutableList()
        var acc = state.buffer.toBigDecimalOrNull() ?: BigDecimal.ZERO
        while (ops.isNotEmpty()) {
            val left = values.removeAt(values.lastIndex)
            val pending = ops.removeAt(ops.lastIndex)
            acc = apply(left, pending, acc) ?: return errorState(pending, acc)
        }
        if (acc.abs() > MAX_ABS) return CalcState(error = "金额超出上限")
        return CalcState(buffer = plain(acc), evaluated = true)
    }

    /** 取当前输入对应的金额（分）；非法返回 null。 */
    fun centsOf(state: CalcState): Long? {
        if (state.error != null) return null
        val cents = MoneyFormatter.yuanToCents(state.buffer) ?: return null
        return if (cents <= 0 || MoneyFormatter.exceedsLimit(cents)) null else cents
    }

    fun expressionOf(state: CalcState): String {
        if (state.evaluated) return ""
        if (state.values.isEmpty()) return ""
        val sb = StringBuilder()
        state.values.forEachIndexed { i, v ->
            if (i > 0) sb.append(' ')
            sb.append(plain(v))
            if (i < state.ops.size) sb.append(' ').append(state.ops[i].symbol)
        }
        if (state.buffer != "0" && state.buffer.isNotEmpty()) sb.append(' ').append(state.buffer)
        return sb.toString()
    }

    /** 二元运算；除零返回 null。 */
    private fun apply(
        left: BigDecimal,
        op: CalcOp,
        right: BigDecimal,
    ): BigDecimal? {
        val raw =
            when (op) {
                CalcOp.ADD -> left + right
                CalcOp.SUB -> left - right
                CalcOp.MUL -> left * right
                CalcOp.DIV -> if (right.compareTo(BigDecimal.ZERO) == 0) null else left.divide(right, 6, RoundingMode.HALF_UP)
            } ?: return null
        return raw.setScale(MAX_DECIMALS, RoundingMode.HALF_UP)
    }

    private fun errorState(
        op: CalcOp,
        right: BigDecimal,
    ): CalcState =
        if (op == CalcOp.DIV && right.compareTo(BigDecimal.ZERO) == 0) {
            CalcState(error = "不能除以 0")
        } else {
            CalcState(error = "计算错误")
        }

    private fun decimalsOf(buf: String): Int {
        val idx = buf.indexOf('.')
        return if (idx < 0) 0 else buf.length - idx - 1
    }

    /** BigDecimal 转展示文本：去掉多余的 0，但至少保留整数位。 */
    fun plain(v: BigDecimal): String {
        val trimmed = v.setScale(MAX_DECIMALS, RoundingMode.HALF_UP).stripTrailingZeros()
        return if (trimmed.scale() < 0) trimmed.setScale(0).toPlainString() else trimmed.toPlainString()
    }
}
