package com.xxjz.ledger.feature.report

import com.xxjz.ledger.core.common.DateUtils
import com.xxjz.ledger.domain.model.TxnType
import com.xxjz.ledger.domain.repository.CategoryRepository
import com.xxjz.ledger.domain.repository.TxnRepository
import javax.inject.Inject

/**
 * 年度总结计算（AC-42）：12 月双柱、Top3 分类、Top3 商家、全年结余、与去年对比。
 *
 * 单独抽成 usecase 而不是放在 ViewModel 里，是因为 P07 与 P09 都要用，
 * 且 Hilt 不允许一个 @HiltViewModel 直接注入另一个 @HiltViewModel。
 *
 * 口径与报表一致：排除软删除与「不计入收支」（AC-43）。
 */
class YearSummaryUseCase
    @Inject
    constructor(
        private val txnRepository: TxnRepository,
        private val categoryRepository: CategoryRepository,
    ) {
        suspend operator fun invoke(year: Int): YearSummary {
            val categories = categoryRepository.listAll()
            val parentOf = categories.associate { it.id to (it.parentId ?: it.id) }
            val nameOf = categories.associate { it.id to it.name }

            val current = counted(year)
            val prev = counted(year - 1)
            val expense = current.filter { it.type == TxnType.EXPENSE }
            val income = current.filter { it.type == TxnType.INCOME }

            val merchantRanks =
                expense.filter { !it.merchantName.isNullOrBlank() }
                    .groupBy { it.merchantName!! }
                    .map { (name, list) ->
                        RankItem(
                            id = "m_$name",
                            name = name,
                            amount = list.sumOf { it.amount },
                            count = list.size,
                            percent = 0,
                        )
                    }

            val catRanks =
                ReportMath.buildRanks(
                    expense.groupBy { it.categoryId?.let { id -> parentOf[id] ?: id } ?: "__none__" }
                        .map { (id, list) ->
                            Triple(id, if (id == "__none__") "未分类" else nameOf[id] ?: "未分类", list.sumOf { it.amount })
                        },
                )

            return YearSummary(
                year = year,
                months =
                    ReportMath.yearMonths(
                        expense.groupingBy { DateUtils.toDate(it.dateTime).monthValue }.fold(0L) { a, t -> a + t.amount },
                        income.groupingBy { DateUtils.toDate(it.dateTime).monthValue }.fold(0L) { a, t -> a + t.amount },
                    ),
                topCategories = ReportMath.topN(catRanks, 3),
                topMerchants = ReportMath.topN(merchantRanks, 3),
                expense = expense.sumOf { it.amount },
                income = income.sumOf { it.amount },
                lastYearExpense = prev.filter { it.type == TxnType.EXPENSE }.sumOf { it.amount },
                lastYearIncome = prev.filter { it.type == TxnType.INCOME }.sumOf { it.amount },
            )
        }

        private suspend fun counted(year: Int) =
            txnRepository
                .listInRange(DateUtils.yearRange(year).first, DateUtils.yearRange(year).last)
                .filter { !it.isDeleted && !it.excludedFromStats }
    }
