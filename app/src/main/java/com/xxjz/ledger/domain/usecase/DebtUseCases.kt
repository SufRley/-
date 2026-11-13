package com.xxjz.ledger.domain.usecase

import com.xxjz.ledger.core.common.AppError
import com.xxjz.ledger.core.common.AppResult
import com.xxjz.ledger.core.common.DateUtils
import com.xxjz.ledger.core.common.MoneyFormatter
import com.xxjz.ledger.data.local.seed.SeedData
import com.xxjz.ledger.domain.model.Debt
import com.xxjz.ledger.domain.model.DebtDirection
import com.xxjz.ledger.domain.model.Txn
import com.xxjz.ledger.domain.model.TxnType
import com.xxjz.ledger.domain.repository.DebtRepository
import com.xxjz.ledger.domain.repository.TxnRepository
import javax.inject.Inject

/** 借贷编辑草稿。 */
data class DebtDraft(
    val id: String? = null,
    val direction: DebtDirection = DebtDirection.LEND,
    val counterparty: String,
    val principalCents: Long,
    val accountId: String? = null,
    val startAt: Long = System.currentTimeMillis(),
    val dueAt: Long? = null,
    val note: String = "",
)

/** 新建 / 编辑借贷记录（AC-63）。 */
class SaveDebtUseCase
    @Inject
    constructor(
        private val debtRepository: DebtRepository,
    ) {
        suspend operator fun invoke(draft: DebtDraft): AppResult<String> {
            val error =
                when {
                    draft.counterparty.isBlank() -> AppError.DebtCounterpartyRequired
                    draft.principalCents <= 0L -> AppError.AmountNotPositive
                    draft.principalCents > MoneyFormatter.MAX_CENTS -> AppError.AmountTooLarge
                    draft.dueAt != null && draft.dueAt < draft.startAt -> AppError.Custom("还款日期不能早于借款日期")
                    else -> null
                }
            if (error != null) return AppResult.Failure(error)

            val existing = draft.id?.let { debtRepository.byId(it) }
            if (draft.id != null && existing == null) return AppResult.Failure(AppError.NotFound)
            val debt =
                Debt(
                    id = existing?.id ?: SeedData.newId("debt"),
                    direction = draft.direction,
                    counterparty = draft.counterparty.trim(),
                    principal = draft.principalCents,
                    accountId = draft.accountId,
                    startAt = draft.startAt,
                    dueAt = draft.dueAt,
                    note = draft.note.trim(),
                    settled = existing?.settled ?: false,
                    writtenOff = existing?.writtenOff ?: false,
                )
            debtRepository.upsert(debt)
            return AppResult.Success(debt.id)
        }
    }

/** 一次还款的结果。 */
data class RepayResult(
    val txnId: String,
    /** 还完后仍欠（分）。 */
    val remaining: Long,
    /** 是否因此自动结清（AC-64）。 */
    val settled: Boolean,
)

/**
 * 记一笔还款（AC-64）。
 *
 * - 支持部分还款：金额必须 1..剩余，还完自动结清；
 * - 每笔还款都落一条 `type = DEBT` 的流水并带 `debtId`，剩余额由 SUM 实时计算，不存冗余字段；
 * - 借贷流水不参与收支统计（AC-12），也不改账户余额，仅用于台账追溯。
 */
class RepayDebtUseCase
    @Inject
    constructor(
        private val debtRepository: DebtRepository,
        private val txnRepository: TxnRepository,
    ) {
        suspend operator fun invoke(
            debtId: String,
            amountCents: Long,
            now: Long = System.currentTimeMillis(),
            note: String = "",
        ): AppResult<RepayResult> {
            val debt = debtRepository.byId(debtId) ?: return AppResult.Failure(AppError.NotFound)
            if (debt.writtenOff) return AppResult.Failure(AppError.Custom("该笔借贷已标记坏账"))
            if (debt.settled) return AppResult.Failure(AppError.Custom("该笔借贷已结清"))
            if (amountCents <= 0L) return AppResult.Failure(AppError.AmountNotPositive)
            if (amountCents > debt.remaining) return AppResult.Failure(AppError.Custom("还款金额不能超过剩余金额"))

            val txn =
                Txn(
                    id = SeedData.newId("txn"),
                    type = TxnType.DEBT,
                    amount = amountCents,
                    categoryId = null,
                    accountId = debt.accountId,
                    dateTime = now,
                    dateKey = DateUtils.dateKeyOf(now),
                    note = note.ifBlank { "${if (debt.direction == DebtDirection.LEND) "收回" else "还款"}：${debt.counterparty}" }.take(200),
                    debtId = debt.id,
                    createdAt = now,
                    updatedAt = now,
                )
            txnRepository.insert(txn)

            val remaining = debt.remaining - amountCents
            val settled = remaining <= 0L
            if (settled) debtRepository.upsert(debt.copy(settled = true))
            return AppResult.Success(RepayResult(txn.id, remaining.coerceAtLeast(0L), settled))
        }
    }

/** 坏账标记（AC-65）：结清台账但保留记录，金额计入损失口径。 */
class MarkDebtBadUseCase
    @Inject
    constructor(
        private val debtRepository: DebtRepository,
    ) {
        suspend fun mark(
            debtId: String,
            bad: Boolean = true,
        ): AppResult<Unit> {
            val debt = debtRepository.byId(debtId) ?: return AppResult.Failure(AppError.NotFound)
            debtRepository.upsert(debt.copy(writtenOff = bad, settled = bad || debt.settled))
            return AppResult.Success(Unit)
        }
    }
