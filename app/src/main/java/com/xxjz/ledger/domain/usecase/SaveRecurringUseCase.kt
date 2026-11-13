package com.xxjz.ledger.domain.usecase

import com.xxjz.ledger.core.common.AppError
import com.xxjz.ledger.core.common.AppResult
import com.xxjz.ledger.core.common.DateUtils
import com.xxjz.ledger.core.common.MoneyFormatter
import com.xxjz.ledger.data.local.seed.SeedData
import com.xxjz.ledger.domain.model.RecurringPeriod
import com.xxjz.ledger.domain.model.RecurringRule
import com.xxjz.ledger.domain.model.TxnType
import com.xxjz.ledger.domain.repository.RecurringRepository
import com.xxjz.ledger.feature.recurring.RecurringCalc
import javax.inject.Inject

/** 结束条件：永不 / 执行 N 次 / 指定日期（AC-56）。 */
enum class RecurringEndMode { FOREVER, COUNT, UNTIL }

/** 周期规则编辑草稿。 */
data class RecurringDraft(
    val id: String? = null,
    val type: TxnType = TxnType.EXPENSE,
    val amountCents: Long,
    val categoryId: String? = null,
    val accountId: String? = null,
    val toAccountId: String? = null,
    val note: String = "",
    val period: RecurringPeriod = RecurringPeriod.MONTH,
    val interval: Int = 1,
    /** 第一次执行的时刻（含时分）。 */
    val startAt: Long,
    val endMode: RecurringEndMode = RecurringEndMode.FOREVER,
    val maxTimes: Int? = null,
    val endAt: Long? = null,
)

/**
 * 新建 / 编辑周期规则（AC-55、AC-56）。
 *
 * 已有规则改金额或分类时保留游标与已执行次数，只有改「开始时间」才重置游标并清零次数，
 * 否则用户改个金额就会把历史执行记录清空、把错过的时间全部补出来。
 */
class SaveRecurringUseCase
    @Inject
    constructor(
        private val recurringRepository: RecurringRepository,
    ) {
        suspend operator fun invoke(
            draft: RecurringDraft,
            now: Long = System.currentTimeMillis(),
        ): AppResult<String> {
            validate(draft)?.let { return AppResult.Failure(it) }
            val existing = draft.id?.let { recurringRepository.byId(it) }
            if (draft.id != null && existing == null) return AppResult.Failure(AppError.NotFound)

            val startChanged = existing == null || !sameDay(existing.nextRunAt, draft.startAt)
            val rule =
                RecurringRule(
                    id = existing?.id ?: SeedData.newId("rec"),
                    type = draft.type,
                    amount = draft.amountCents,
                    categoryId = if (draft.type == TxnType.TRANSFER) null else draft.categoryId,
                    accountId = draft.accountId,
                    toAccountId = if (draft.type == TxnType.TRANSFER) draft.toAccountId else null,
                    note = draft.note.trim(),
                    period = draft.period,
                    interval = draft.interval.coerceAtLeast(1),
                    nextRunAt = if (startChanged) draft.startAt else existing.nextRunAt,
                    endAt = if (draft.endMode == RecurringEndMode.UNTIL) draft.endAt else null,
                    maxTimes = if (draft.endMode == RecurringEndMode.COUNT) draft.maxTimes else null,
                    runTimes = if (startChanged) 0 else existing.runTimes,
                    paused = existing?.paused ?: false,
                )
            recurringRepository.upsert(rule)
            return AppResult.Success(rule.id)
        }

        private fun sameDay(
            a: Long,
            b: Long,
        ): Boolean = DateUtils.dateKeyOf(a) == DateUtils.dateKeyOf(b)

        private fun validate(draft: RecurringDraft): AppError? =
            when {
                draft.amountCents <= 0L -> AppError.AmountNotPositive
                draft.amountCents > MoneyFormatter.MAX_CENTS -> AppError.AmountTooLarge
                draft.type != TxnType.TRANSFER && draft.categoryId.isNullOrBlank() -> AppError.CategoryRequired
                draft.accountId.isNullOrBlank() -> AppError.AccountRequired
                draft.type == TxnType.TRANSFER && draft.toAccountId == draft.accountId -> AppError.SameAccount
                draft.endMode == RecurringEndMode.COUNT && (draft.maxTimes ?: 0) <= 0 ->
                    AppError.Custom("执行次数必须大于 0")
                draft.endMode == RecurringEndMode.UNTIL && draft.endAt != null && draft.endAt < draft.startAt ->
                    AppError.Custom("结束日期不能早于开始日期")
                else -> null
            }
    }

/** 暂停 / 恢复（AC-54）：恢复时游标从当前时刻重新推算，暂停期间不补执行。 */
class ToggleRecurringUseCase
    @Inject
    constructor(
        private val recurringRepository: RecurringRepository,
    ) {
        suspend fun pause(ruleId: String): Boolean {
            val rule = recurringRepository.byId(ruleId) ?: return false
            if (rule.paused) return true
            recurringRepository.setPaused(rule.id, true, rule.nextRunAt)
            return true
        }

        suspend fun resume(
            ruleId: String,
            now: Long = System.currentTimeMillis(),
        ): Boolean {
            val rule = recurringRepository.byId(ruleId) ?: return false
            if (!rule.paused) return true
            recurringRepository.setPaused(rule.id, false, RecurringCalc.nextAfterResume(rule, now))
            return true
        }

        suspend fun toggle(
            ruleId: String,
            now: Long = System.currentTimeMillis(),
        ): Boolean {
            val rule = recurringRepository.byId(ruleId) ?: return false
            return if (rule.paused) resume(ruleId, now) else pause(ruleId)
        }
    }
