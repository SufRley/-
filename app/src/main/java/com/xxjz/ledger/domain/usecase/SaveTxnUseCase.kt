package com.xxjz.ledger.domain.usecase

import com.xxjz.ledger.core.common.AppError
import com.xxjz.ledger.core.common.AppResult
import com.xxjz.ledger.core.common.DateUtils
import com.xxjz.ledger.core.common.MoneyFormatter
import com.xxjz.ledger.data.local.seed.SeedData
import com.xxjz.ledger.domain.model.Txn
import com.xxjz.ledger.domain.model.TxnType
import com.xxjz.ledger.domain.repository.CategoryRepository
import com.xxjz.ledger.domain.repository.TxnRepository
import javax.inject.Inject

/**
 * 记账面板提交时的草稿。所有业务校验都收敛在 [SaveTxnUseCase]，UI 只负责收集输入。
 */
data class TxnDraft(
    val id: String? = null,
    val type: TxnType,
    /** 金额（分），必须为正。 */
    val amountCents: Long,
    val categoryId: String? = null,
    val accountId: String? = null,
    val toAccountId: String? = null,
    val feeCents: Long = 0,
    val dateTime: Long,
    val note: String = "",
    val excludedFromStats: Boolean = false,
    val pendingReimbursement: Boolean = false,
    /** 收据照片路径（v1.9.1），null 表示没有附图。 */
    val photoPath: String? = null,
)

/**
 * 保存 / 更新一笔账目。新建与编辑共用（`draft.id` 为空即新建）。
 *
 * 校验项（AC-02 / AC-04 / AC-15）：
 * - 金额 > 0 且不超过 9 位上限；
 * - 支出 / 收入必须有分类；
 * - 必须有账户；转账的转出与转入不能相同，且手续费不能为负。
 */
class SaveTxnUseCase
    @Inject
    constructor(
        private val txnRepository: TxnRepository,
        private val categoryRepository: CategoryRepository,
    ) {
        suspend operator fun invoke(
            draft: TxnDraft,
            now: Long = System.currentTimeMillis(),
        ): AppResult<String> {
            val error = validate(draft)
            if (error != null) return AppResult.Failure(error)

            val existing = draft.id?.let { txnRepository.byId(it) }
            if (draft.id != null && existing == null) return AppResult.Failure(AppError.NotFound)

            val txn =
                Txn(
                    id = existing?.id ?: SeedData.newId("txn"),
                    type = draft.type,
                    amount = draft.amountCents,
                    categoryId =
                        if (draft.type == TxnType.EXPENSE || draft.type == TxnType.INCOME) {
                            draft.categoryId
                        } else {
                            null
                        },
                    accountId = draft.accountId,
                    toAccountId = if (draft.type == TxnType.TRANSFER) draft.toAccountId else null,
                    fee = if (draft.type == TxnType.TRANSFER) draft.feeCents else 0,
                    dateTime = draft.dateTime,
                    dateKey = DateUtils.dateKeyOf(draft.dateTime),
                    note = draft.note.trim(),
                    excludedFromStats = draft.excludedFromStats,
                    pendingReimbursement = draft.pendingReimbursement && draft.type == TxnType.EXPENSE,
                    // 编辑时不传 photoPath 就沿用原图：否则改个备注会把附图弄丢
                    photoPath = draft.photoPath ?: existing?.photoPath,
                    createdAt = existing?.createdAt ?: now,
                    updatedAt = now,
                )

            if (existing == null) {
                txnRepository.insert(txn)
            } else {
                txnRepository.update(txn)
            }

            // 分类使用次数只在新记账或换分类时累加，用于「常用」排序（AC-05）。
            val categoryId = txn.categoryId
            if (categoryId != null && categoryId != existing?.categoryId) {
                categoryRepository.increaseUsage(categoryId)
            }
            return AppResult.Success(txn.id)
        }

        private fun validate(draft: TxnDraft): AppError? {
            if (draft.amountCents <= 0) return AppError.AmountNotPositive
            if (MoneyFormatter.exceedsLimit(draft.amountCents)) return AppError.AmountTooLarge
            if (draft.feeCents < 0) return AppError.Custom("手续费不能为负")
            if (draft.accountId.isNullOrBlank()) return AppError.AccountRequired
            when (draft.type) {
                TxnType.EXPENSE, TxnType.INCOME -> if (draft.categoryId.isNullOrBlank()) return AppError.CategoryRequired
                TxnType.TRANSFER -> {
                    if (draft.toAccountId.isNullOrBlank()) return AppError.AccountRequired
                    if (draft.toAccountId == draft.accountId) return AppError.SameAccount
                }
                TxnType.DEBT -> Unit
            }
            return null
        }
    }
