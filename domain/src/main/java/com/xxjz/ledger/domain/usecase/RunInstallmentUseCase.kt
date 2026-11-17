package com.xxjz.ledger.domain.usecase

import com.xxjz.ledger.core.common.DateUtils
import com.xxjz.ledger.domain.model.Ids
import com.xxjz.ledger.domain.model.Installment
import com.xxjz.ledger.domain.model.InstallmentSchedule
import com.xxjz.ledger.domain.model.Txn
import com.xxjz.ledger.domain.repository.InstallmentRepository
import com.xxjz.ledger.domain.repository.TxnRepository
import kotlinx.coroutines.flow.first
import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject

/**
 * 分期自动出账引擎（v1.9.5 第 9 条）。
 *
 * 与 [RunRecurringUseCase] 同一套思路：**先算计划、再落库、最后把游标前移并写回**。
 * 因此连着调用两次只有第一次会生成流水，App 启动补执行与每日后台任务可以放心重复触发。
 *
 * 一次只补一期：分期的本质是"每月多一笔"，跨了三个月没打开 App 时逐期补上，
 * 但每期都落在它自己的月份里（`nextRunAt` 逐次 +1 月），不会把三笔都堆在今天。
 */
class RunInstallmentUseCase
    @Inject
    constructor(
        private val installmentRepository: InstallmentRepository,
        private val txnRepository: TxnRepository,
    ) {
        /** 补执行所有到期分期，返回本次生成的流水条数。 */
        suspend operator fun invoke(at: Long = System.currentTimeMillis()): Int {
            val plans = installmentRepository.observeAll().first()
            var created = 0
            for (plan in plans) {
                created += runPlan(plan, at)
            }
            return created
        }

        /** 单条计划补齐：可能连续补多期（用户几个月没打开）。 */
        private suspend fun runPlan(
            plan: Installment,
            at: Long,
        ): Int {
            if (plan.settled || plan.nextRunAt <= 0L) return 0
            val source = plan.txnId?.let { txnRepository.byId(it) }
            if (source == null) {
                // 原流水被物理删掉了：停止排期，别每月凭空造账
                installmentRepository.upsert(plan.copy(nextRunAt = 0))
                return 0
            }
            var cursor = plan
            var count = 0
            var guard = 0
            val schedule = InstallmentSchedule.of(plan)
            while (!cursor.settled && cursor.nextRunAt in 1..at && guard < MAX_CATCH_UP) {
                val next = cursor.paidPeriods + 2
                val period = schedule.getOrNull(next - 1) ?: break
                txnRepository.insert(buildTxn(source, cursor, next, period.totalCents, cursor.nextRunAt))
                cursor =
                    cursor.copy(
                        paidPeriods = cursor.paidPeriods + 1,
                        nextRunAt = plusMonths(cursor.nextRunAt, 1),
                    )
                count += 1
                guard += 1
            }
            // 结清后把游标清零，免得下次又被扫到
            if (cursor.settled) cursor = cursor.copy(nextRunAt = 0)
            if (cursor != plan) installmentRepository.upsert(cursor)
            return count
        }

        /** 每期一条新流水：除金额与备注外全部继承原账，日期落在该期自己的月份。 */
        private fun buildTxn(
            source: Txn,
            plan: Installment,
            periodIndex: Int,
            cents: Long,
            runAt: Long,
        ): Txn =
            source.copy(
                id = Ids.newId("txn"),
                amount = cents,
                note = "${source.note}（分期 $periodIndex/${plan.periods}）".trim().take(200),
                dateTime = runAt,
                dateKey = DateUtils.dateKeyOf(runAt),
                createdAt = runAt,
                updatedAt = runAt,
                deletedAt = null,
            )

        private fun plusMonths(
            millis: Long,
            months: Long,
        ): Long {
            val dt = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(millis), ZoneId.systemDefault())
            return dt.plusMonths(months).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }

        companion object {
            /** 单条计划单次补执行上限：60 期是合法上限，留一点余量。 */
            const val MAX_CATCH_UP = 72
        }
    }
