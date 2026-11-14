package com.xxjz.ledger.feature.ledger

import com.xxjz.ledger.domain.model.BudgetPeriod
import com.xxjz.ledger.domain.model.BudgetScope
import com.xxjz.ledger.domain.usecase.EffectiveBudget

/**
 * 明细页底部预算概览卡的数据（v1.8.0 / PRD F4）。
 *
 * 只关心「总预算」这一条：按 PRD，明细页只要一条总进度，分类预算的细账仍在预算页看。
 * 抽成独立数据类是为了让「超支判定」「剩余」「进度比例」这三件事可以被单测覆盖，
 * 而不是埋在 Compose 里靠肉眼核对。
 */
data class BudgetCardState(
    val budgetId: String? = null,
    /** 当期预算额度（分）；null 表示没设预算。 */
    val limitCents: Long? = null,
    /** 已用（分）。 */
    val usedCents: Long = 0,
) {
    val hasBudget: Boolean get() = limitCents != null && limitCents > 0

    /** 剩余（分），可以为负表示超支。 */
    val remainingCents: Long get() = (limitCents ?: 0) - usedCents

    val overspent: Boolean get() = hasBudget && usedCents > (limitCents ?: 0)

    /** 进度比例，限制在 0..1，超支时进度条画满即可（颜色会变红提示）。 */
    val progress: Float
        get() {
            val limit = limitCents ?: return 0f
            if (limit <= 0) return 0f
            return (usedCents.toFloat() / limit).coerceIn(0f, 1f)
        }

    companion object {
        /** 从该月生效预算里挑出「总预算」这条，构造卡片数据。 */
        fun from(
            budgets: List<EffectiveBudget>,
            measuredExpense: Long,
        ): BudgetCardState {
            val total =
                budgets
                    .map { it.budget }
                    .filter { it.enabled && it.deletedAt == null && it.scope == BudgetScope.TOTAL && it.period == BudgetPeriod.MONTH }
                    .minByOrNull { it.sortOrder }
            return BudgetCardState(
                budgetId = total?.id,
                limitCents = total?.amount,
                usedCents = measuredExpense,
            )
        }
    }
}
