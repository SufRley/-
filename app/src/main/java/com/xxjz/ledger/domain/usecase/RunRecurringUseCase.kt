package com.xxjz.ledger.domain.usecase

import com.xxjz.ledger.core.common.AppError
import com.xxjz.ledger.core.common.AppResult
import com.xxjz.ledger.core.common.DateUtils
import com.xxjz.ledger.domain.model.Ids
import com.xxjz.ledger.domain.model.RecurringCalc
import com.xxjz.ledger.domain.model.RecurringRule
import com.xxjz.ledger.domain.model.Txn
import com.xxjz.ledger.domain.model.TxnType
import com.xxjz.ledger.domain.repository.RecurringRepository
import com.xxjz.ledger.domain.repository.TxnRepository
import javax.inject.Inject

/**
 * 周期记账执行引擎（AC-52、AC-53、AC-54、AC-58）。
 *
 * 幂等保证：每条规则执行前先算 [RecurringCalc.plan]，执行后立刻把 `nextRunAt` 前移并写回，
 * 因此连着调用两次只有第一次会生成账目；App 启动补执行与后台任务可以放心重复触发。
 *
 * 暂停的规则不会进 [RecurringRepository.due]，暂停期间的账目一律不补（PRD 明确）。
 */
class RunRecurringUseCase
    @Inject
    constructor(
        private val recurringRepository: RecurringRepository,
        private val txnRepository: TxnRepository,
    ) {
        /** 补执行所有到期规则，返回本次生成的账目笔数。 */
        suspend operator fun invoke(at: Long = System.currentTimeMillis()): Int {
            val rules = recurringRepository.due(at)
            var created = 0
            for (rule in rules) {
                val plan = RecurringCalc.plan(rule, at)
                if (plan.runAts.isEmpty()) {
                    // 到期但已无可执行时刻（次数用完 / 越过结束日）：直接收尾，避免下次再被扫到。
                    if (plan.finished || plan.nextRunAt != rule.nextRunAt) {
                        recurringRepository.advance(rule.id, plan.nextRunAt, plan.runTimes, rule.paused || plan.finished)
                    }
                    continue
                }
                plan.runAts.forEach { runAt ->
                    txnRepository.insert(buildTxn(rule, runAt))
                    created++
                }
                recurringRepository.advance(
                    rule.id,
                    plan.nextRunAt,
                    plan.runTimes,
                    rule.paused || plan.finished,
                )
            }
            return created
        }

        /** 「立即执行」：手动生成一笔，并把游标推到下个周期（AC-54）。 */
        suspend fun runNow(
            ruleId: String,
            at: Long = System.currentTimeMillis(),
        ): AppResult<String> {
            val rule = recurringRepository.byId(ruleId) ?: return AppResult.Failure(AppError.NotFound)
            val txn = buildTxn(rule, at)
            txnRepository.insert(txn)
            val times = rule.runTimes + 1
            val next =
                if (rule.nextRunAt <= at) {
                    RecurringCalc.nextAfterResume(rule, at)
                } else {
                    rule.nextRunAt
                }
            val finished =
                (rule.maxTimes != null && times >= rule.maxTimes) ||
                    (rule.endAt != null && next > rule.endAt)
            recurringRepository.advance(rule.id, next, times, rule.paused || finished)
            return AppResult.Success(txn.id)
        }

        private fun buildTxn(
            rule: RecurringRule,
            runAt: Long,
        ): Txn =
            Txn(
                id = Ids.newId("txn"),
                type = rule.type,
                amount = rule.amount,
                categoryId = if (rule.type == TxnType.EXPENSE || rule.type == TxnType.INCOME) rule.categoryId else null,
                accountId = rule.accountId,
                toAccountId = if (rule.type == TxnType.TRANSFER) rule.toAccountId else null,
                dateTime = runAt,
                dateKey = DateUtils.dateKeyOf(runAt),
                note = rule.note,
                createdAt = runAt,
                updatedAt = runAt,
            )
    }
