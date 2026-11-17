package com.xxjz.ledger.domain.usecase

import com.xxjz.ledger.core.common.AppError
import com.xxjz.ledger.core.common.AppResult
import com.xxjz.ledger.core.common.MoneyFormatter
import com.xxjz.ledger.domain.model.Budget
import com.xxjz.ledger.domain.model.BudgetPeriod
import com.xxjz.ledger.domain.model.BudgetScope
import com.xxjz.ledger.domain.model.Ids
import com.xxjz.ledger.domain.repository.BudgetRepository
import javax.inject.Inject

/** 预算编辑页的草稿。 */
data class BudgetDraft(
    val id: String? = null,
    val name: String = "",
    val period: BudgetPeriod = BudgetPeriod.MONTH,
    val scope: BudgetScope = BudgetScope.TOTAL,
    val categoryIds: List<String> = emptyList(),
    /** 额度（分）。 */
    val amountCents: Long,
    val enabled: Boolean = true,
)

/**
 * 保存预算（AC-44 / AC-45）。
 *
 * 写库前先调用 [GetBudgetForMonthUseCase.materialize]，把「沿用上月」的配置落成本月版本，
 * 这样改本月预算不会回头改动历史月份，也不会被下个月的继承覆盖。
 */
class SaveBudgetUseCase
    @Inject
    constructor(
        private val budgetRepository: BudgetRepository,
        private val getBudgetForMonth: GetBudgetForMonthUseCase,
    ) {
        suspend operator fun invoke(
            draft: BudgetDraft,
            monthKey: Int,
        ): AppResult<String> {
            if (draft.amountCents <= 0) return AppResult.Failure(AppError.AmountNotPositive)
            if (MoneyFormatter.exceedsLimit(draft.amountCents)) return AppResult.Failure(AppError.AmountTooLarge)
            if (draft.scope == BudgetScope.CATEGORY && draft.categoryIds.isEmpty()) {
                return AppResult.Failure(AppError.Custom("请选择要纳入预算的分类"))
            }

            getBudgetForMonth.materialize(monthKey)

            val id = draft.id ?: Ids.newId("budget")
            val name =
                draft.name.trim().ifBlank {
                    when (draft.scope) {
                        BudgetScope.TOTAL -> "总预算"
                        BudgetScope.CATEGORY -> "分类预算"
                    }
                }
            budgetRepository.upsert(
                Budget(
                    id = id,
                    monthKey = monthKey,
                    name = name,
                    period = draft.period,
                    scope = draft.scope,
                    categoryIds = draft.categoryIds.distinct(),
                    amount = draft.amountCents,
                    enabled = draft.enabled,
                ),
            )
            return AppResult.Success(id)
        }
    }

/** 删除预算：进回收站，不影响历史月份的统计（AC-50）。 */
class DeleteBudgetUseCase
    @Inject
    constructor(
        private val budgetRepository: BudgetRepository,
        private val getBudgetForMonth: GetBudgetForMonthUseCase,
    ) {
        suspend operator fun invoke(
            id: String,
            monthKey: Int,
            now: Long = System.currentTimeMillis(),
        ) {
            getBudgetForMonth.materialize(monthKey)
            budgetRepository.softDelete(id = id, monthKey = monthKey, at = now)
        }
    }
