package com.xxjz.ledger.feature.ledger

import com.xxjz.ledger.domain.model.Budget
import com.xxjz.ledger.domain.model.BudgetPeriod
import com.xxjz.ledger.domain.model.BudgetScope
import com.xxjz.ledger.domain.usecase.EffectiveBudget
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 明细页底部预算概览卡（v1.8.0 / PRD F4、D7）。
 *
 * 四态都要覆盖：没设预算 / 正常 / 刚好用完 / 超支。超支时进度条画满且转红，
 * 「剩余」文案换成「已超支」——这三件事决定了用户第一眼能不能看懂。
 */
@DisplayName("预算概览卡")
class BudgetCardStateTest {
    private fun total(
        amount: Long,
        enabled: Boolean = true,
    ) = EffectiveBudget(
        Budget(
            id = "b1",
            monthKey = 202611,
            name = "总预算",
            scope = BudgetScope.TOTAL,
            period = BudgetPeriod.MONTH,
            amount = amount,
            enabled = enabled,
        ),
    )

    @Test
    @DisplayName("没设预算：hasBudget 为 false，显示引导文案")
    fun noBudget() {
        val card = BudgetCardState.from(emptyList(), measuredExpense = 500_00)
        assertFalse(card.hasBudget)
        assertEquals(0f, card.progress)
        assertEquals(null, card.limitCents)
    }

    @Test
    @DisplayName("正常：剩余 = 预算 − 已用，进度按比例")
    fun normal() {
        val card = BudgetCardState.from(listOf(total(1_000_00)), measuredExpense = 250_00)
        assertTrue(card.hasBudget)
        assertEquals(750_00, card.remainingCents)
        assertEquals(0.25f, card.progress, 0.001f)
        assertFalse(card.overspent)
    }

    @Test
    @DisplayName("刚好用完：剩余 0，不算超支")
    fun exactlyUsedUp() {
        val card = BudgetCardState.from(listOf(total(500_00)), measuredExpense = 500_00)
        assertEquals(0L, card.remainingCents)
        assertEquals(1f, card.progress, 0.001f)
        assertFalse(card.overspent)
    }

    @Test
    @DisplayName("超支：剩余为负、进度画满、标记超支")
    fun overspent() {
        val card = BudgetCardState.from(listOf(total(500_00)), measuredExpense = 720_00)
        assertEquals(-220_00, card.remainingCents)
        assertEquals(1f, card.progress, 0.001f, "超支时进度条画满即可，不能溢出")
        assertTrue(card.overspent)
    }

    @Test
    @DisplayName("停用的预算不算数；分类预算不占明细页这条总进度")
    fun disabledAndCategoryBudgetIgnored() {
        val disabled = BudgetCardState.from(listOf(total(1_000_00, enabled = false)), measuredExpense = 100_00)
        assertFalse(disabled.hasBudget)

        val category =
            EffectiveBudget(
                Budget(
                    id = "c1",
                    monthKey = 202611,
                    name = "餐饮",
                    scope = BudgetScope.CATEGORY,
                    categoryIds = listOf("food"),
                    amount = 300_00,
                ),
            )
        assertFalse(BudgetCardState.from(listOf(category), measuredExpense = 100_00).hasBudget)
    }

    @Test
    @DisplayName("已删除的预算不进卡片")
    fun deletedBudgetIgnored() {
        val deleted =
            EffectiveBudget(
                Budget(id = "b9", monthKey = 202611, name = "旧预算", amount = 100_00, deletedAt = 1_700_000_000_000),
            )
        assertFalse(BudgetCardState.from(listOf(deleted), measuredExpense = 0).hasBudget)
    }

    @Test
    @DisplayName("额度为 0 视为没设预算，避免出现「0 元预算永远超支」")
    fun zeroLimitTreatedAsNone() {
        val card = BudgetCardState.from(listOf(total(0)), measuredExpense = 100)
        assertFalse(card.hasBudget)
        assertFalse(card.overspent)
    }
}
