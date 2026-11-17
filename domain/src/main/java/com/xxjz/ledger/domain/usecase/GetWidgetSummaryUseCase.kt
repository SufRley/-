package com.xxjz.ledger.domain.usecase

import com.xxjz.ledger.core.common.DateUtils
import com.xxjz.ledger.domain.model.WidgetSummary
import com.xxjz.ledger.domain.model.WidgetSummaryBuilder
import com.xxjz.ledger.domain.model.MonthlySummary
import com.xxjz.ledger.domain.repository.AccountRepository
import com.xxjz.ledger.domain.repository.TxnRepository
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import javax.inject.Inject

/**
 * 小组件取数（AC-83）：本月收支 + 净资产。
 *
 * 只读一次 `first()` 而不是挂长连接——小组件的刷新由 AppWidget 的 update 周期驱动，
 * 在这里常驻收集只会白占一个协程。
 */
class GetWidgetSummaryUseCase
    @Inject
    constructor(
        private val txnRepository: TxnRepository,
        private val accountRepository: AccountRepository,
        private val assetSummary: GetAssetSummaryUseCase,
    ) {
        suspend operator fun invoke(today: LocalDate = DateUtils.today()): WidgetSummary {
            val range = DateUtils.monthRange(today.year, today.monthValue)
            val (income, expense) = txnRepository.totals(range.first, range.last)
            val month =
                MonthlySummary(
                    monthKey = DateUtils.monthKeyOf(today),
                    income = income,
                    expense = expense,
                    count = 0,
                )
            val assets = assetSummary(accountRepository.observeWithBalances().first())
            // 笔数另取一次「本月有流水」的口径：totals 只统计收支，转账/借贷不在其中，
            // 小组件上「记了几笔」应当是用户实际发生的所有记录。
            val count =
                txnRepository
                    .listInRange(range.first, range.last)
                    .count { !it.isDeleted }
            return WidgetSummaryBuilder.build(month, assets, count)
        }
    }
