package com.xxjz.ledger.feature.editor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** M2 计算器引擎测试：优先级、连续运算、除零、输入约束（AC-03）。 */
@DisplayName("记账计算器引擎")
class CalcEngineTest {
    private fun digits(
        state: CalcState,
        text: String,
    ): CalcState = text.fold(state) { acc, c -> if (c == '.') CalcEngine.dot(acc) else CalcEngine.digit(acc, c) }

    private fun expr(
        text: String,
        ops: List<CalcOp>,
    ): CalcState {
        // 按 "数字:运算符" 的顺序输入，最后一段是待求值数字
        val parts = text.split(" ")
        var s = digits(CalcEngine.initial(), parts.first())
        ops.forEachIndexed { i, op ->
            s = CalcEngine.op(s, op)
            s = digits(s, parts[i + 1])
        }
        return s
    }

    @Test
    fun `先乘除后加减`() {
        val s = CalcEngine.evaluate(expr("12.5 8 2", listOf(CalcOp.ADD, CalcOp.MUL)))
        assertEquals("28.5", s.display)
        assertTrue(s.evaluated)
    }

    @Test
    fun `连续加法`() {
        val s = CalcEngine.evaluate(expr("1 2 3 4", listOf(CalcOp.ADD, CalcOp.ADD, CalcOp.ADD)))
        assertEquals("10", s.display)
    }

    @Test
    fun `连续减法保持左结合`() {
        val s = CalcEngine.evaluate(expr("10 3 2", listOf(CalcOp.SUB, CalcOp.SUB)))
        assertEquals("5", s.display)
    }

    @Test
    fun `除法保留两位小数`() {
        val s = CalcEngine.evaluate(expr("10 4", listOf(CalcOp.DIV)))
        assertEquals("2.5", s.display)
        val third = CalcEngine.evaluate(expr("1 3", listOf(CalcOp.DIV)))
        assertEquals("0.33", third.display)
    }

    @Test
    fun `除以0给出错误`() {
        val s = CalcEngine.evaluate(expr("8 0", listOf(CalcOp.DIV)))
        assertEquals("不能除以 0", s.display)
        assertNull(CalcEngine.centsOf(s))
    }

    @Test
    fun `小数点去重且最多两位`() {
        var s = digits(CalcEngine.initial(), "1.2")
        s = CalcEngine.dot(s)
        assertEquals("1.2", s.display)
        s = digits(s, "345")
        assertEquals("1.23", s.display)
    }

    @Test
    fun `整数最多9位`() {
        val s = digits(CalcEngine.initial(), "1234567890")
        assertEquals("123456789", s.display)
    }

    @Test
    fun `退格删到空归零`() {
        var s = digits(CalcEngine.initial(), "12")
        s = CalcEngine.backspace(s)
        assertEquals("1", s.display)
        s = CalcEngine.backspace(s)
        assertEquals("0", s.display)
    }

    @Test
    fun `求值后输入数字自动开新表达式`() {
        val evaluated = CalcEngine.evaluate(expr("2 3", listOf(CalcOp.ADD)))
        assertEquals("5", evaluated.display)
        val next = CalcEngine.digit(evaluated, '7')
        assertEquals("7", next.display)
        assertFalse(next.evaluated)
    }

    @Test
    fun `求值后金额可保存`() {
        val evaluated = CalcEngine.evaluate(expr("2 3", listOf(CalcOp.ADD)))
        assertTrue(evaluated.canSave)
        assertEquals(500L, CalcEngine.centsOf(evaluated))
        assertNotNull(evaluated.expressionText)
    }

    @Test
    fun `未求值的输入也能取到分`() {
        val s = digits(CalcEngine.initial(), "19.99")
        assertEquals(1999L, CalcEngine.centsOf(s))
        assertNull(CalcEngine.centsOf(CalcEngine.initial()))
    }

    @Test
    fun `运算符回显包含已输入表达式`() {
        val s = expr("12 8", listOf(CalcOp.ADD))
        assertTrue(s.expressionText.contains("12"))
        assertTrue(s.expressionText.contains("+"))
    }

    @Test
    fun `以运算符结尾时求值报错而不是当成加0`() {
        // `100 +` 与 `100 + 0` 的 buffer 都是 "0"，只看 buffer 会把少输的数静默当 0
        var s = digits(CalcEngine.initial(), "100")
        s = CalcEngine.op(s, CalcOp.ADD)
        val evaluated = CalcEngine.evaluate(s)
        assertEquals("表达式不完整，请补全金额", evaluated.error)
        assertFalse(evaluated.evaluated)
        assertNull(CalcEngine.centsOf(evaluated))
    }

    @Test
    fun `运算符后补上数字就能正常求值`() {
        var s = digits(CalcEngine.initial(), "100")
        s = CalcEngine.op(s, CalcOp.ADD)
        s = digits(s, "20")
        val evaluated = CalcEngine.evaluate(s)
        assertNull(evaluated.error)
        assertEquals("120", evaluated.display)
    }

    @Test
    fun `刚按下运算符时退格撤销运算符`() {
        var s = digits(CalcEngine.initial(), "100")
        s = CalcEngine.op(s, CalcOp.ADD)
        assertTrue(s.lastWasOp)
        val undone = CalcEngine.backspace(s)
        assertFalse(undone.lastWasOp)
        assertEquals("100", undone.display)
        assertTrue(undone.values.isEmpty())
    }

    @Test
    fun `运算符后输入0算合法表达式`() {
        // 与上一条的区别：digit('0') 之后 lastWasOp 归 false，属于「写完了」
        var s = digits(CalcEngine.initial(), "100")
        s = CalcEngine.op(s, CalcOp.ADD)
        s = CalcEngine.digit(s, '0')
        val evaluated = CalcEngine.evaluate(s)
        assertNull(evaluated.error)
        assertEquals("100", evaluated.display)
    }
}
