package com.xxjz.ledger.domain.usecase

import com.xxjz.ledger.domain.model.Budget
import com.xxjz.ledger.domain.repository.BudgetRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/** 某个月生效的一条预算；[inheritedFrom] 非空表示沿用了那个月份的配置。 */
data class EffectiveBudget(
    val budget: Budget,
    val inheritedFrom: Int? = null,
)

/**
 * 按月取预算（AC-49）。
 *
 * 规则：
 * - 该月配置过（哪怕全被删了）就用该月的结果，删除不会因为「空」而回退到上月；
 * - 该月从未配置过，则沿用此前最近一个有配置的月份，界面上标注来源月份；
 * - 用户在某一月改动预算时调用 [materialize]，把上月配置复制成本月版本，历史月份不受影响。
 */
class GetBudgetForMonthUseCase
    @Inject
    constructor(
        private val repository: BudgetRepository,
    ) {
        /** 响应式的「该月生效预算」。 */
        @OptIn(ExperimentalCoroutinesApi::class)
        fun observe(monthKey: Int): Flow<List<EffectiveBudget>> =
            repository.observeMonthRows(monthKey).flatMapLatest { rows ->
                if (rows.isNotEmpty()) {
                    flowOf(rows.filter { it.deletedAt == null }.map { EffectiveBudget(it) })
                } else {
                    repository.observeLatestBefore(monthKey).map { prev ->
                        prev.filter { it.deletedAt == null }
                            .map { EffectiveBudget(it.copy(monthKey = monthKey), inheritedFrom = it.monthKey) }
                    }
                }
            }

        /** 一次性取该月生效预算。 */
        suspend operator fun invoke(monthKey: Int): List<EffectiveBudget> {
            val rows = repository.monthRows(monthKey)
            if (rows.isNotEmpty()) return rows.filter { it.deletedAt == null }.map { EffectiveBudget(it) }
            return repository.latestBefore(monthKey)
                .filter { it.deletedAt == null }
                .map { EffectiveBudget(it.copy(monthKey = monthKey), inheritedFrom = it.monthKey) }
        }

        /**
         * 确保 [monthKey] 有一份自己的配置：没有就从上月复制一份（id 不变，只换 monthKey）。
         *
         * 返回该月的全部行（含回收站），供后续单月修改使用。
         */
        suspend fun materialize(monthKey: Int): List<Budget> {
            val rows = repository.monthRows(monthKey)
            if (rows.isNotEmpty()) return rows
            val prev = repository.latestBefore(monthKey).filter { it.deletedAt == null }
            if (prev.isEmpty()) return emptyList()
            val copied = prev.map { it.copy(monthKey = monthKey) }
            repository.upsertAll(copied)
            return copied
        }
    }
