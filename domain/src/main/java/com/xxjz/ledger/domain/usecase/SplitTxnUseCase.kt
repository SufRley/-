package com.xxjz.ledger.domain.usecase

import com.xxjz.ledger.core.common.AppError
import com.xxjz.ledger.core.common.AppResult
import com.xxjz.ledger.core.common.DateUtils
import com.xxjz.ledger.domain.model.Ids
import com.xxjz.ledger.domain.model.Txn
import com.xxjz.ledger.domain.model.TxnType
import com.xxjz.ledger.domain.repository.AccountRepository
import com.xxjz.ledger.domain.repository.TxnRepository
import javax.inject.Inject

/** 拆分明细的一行：这笔钱由哪个账户出、出多少。 */
data class SplitPart(
    val accountId: String,
    val cents: Long,
)

/** 拆分结果。 */
data class SplitResult(
    /** 新生成的流水 id，与入参顺序一致。 */
    val ids: List<String>,
    /** 原流水 id（已进回收站）。 */
    val sourceId: String,
)

/**
 * 拆分一笔流水（v1.9.5 第 8 条）。
 *
 * 上一版是「填份数 → 等额均分」，拆完汇总、余额、报表全都不变，等于什么都没做。
 * 现在的语义是：**一笔账由多个账户共同出钱**，每个账户出多少由用户自己填。
 *
 * 三条硬规则：
 * 1. 至少 2 条、至多 20 条；
 * 2. 各条之和**必须严格等于原金额**——多一分少一分都是另一笔账，不能悄悄抹平；
 * 3. 账户必须是可付款账户（现金 / 储蓄卡 / 信用），投资与借贷不行（v1.9.5 第 3 节口径）。
 *
 * 写入顺序：先插入全部新流水，**最后才**软删原流水。
 * 反过来先删后插的话，中间任何一条失败都会让这笔账凭空消失。
 */
class SplitTxnUseCase
    @Inject
    constructor(
        private val txnRepository: TxnRepository,
        private val accountRepository: AccountRepository,
    ) {
        suspend operator fun invoke(
            txnId: String,
            parts: List<SplitPart>,
            now: Long = System.currentTimeMillis(),
        ): AppResult<SplitResult> {
            val source = txnRepository.byId(txnId) ?: return AppResult.Failure(AppError.NotFound)
            validateShape(source, parts)?.let { return AppResult.Failure(it) }
            validateAccounts(parts)?.let { return AppResult.Failure(it) }
            return write(source, parts, now)
        }

        /** 结构与金额校验：条数、正数、合计。 */
        private fun validateShape(
            source: Txn,
            parts: List<SplitPart>,
        ): AppError? =
            when {
                source.isDeleted -> AppError.NotFound
                // 转账是「一进一出」、借贷是「一借一还」，拆成多笔就变成几笔各自成立的错账
                source.type == TxnType.TRANSFER || source.type == TxnType.DEBT ->
                    AppError.Custom("转账与借贷不能拆分")
                parts.size < MIN_PARTS -> AppError.Custom("至少要两条明细")
                parts.size > MAX_PARTS -> AppError.Custom("最多 20 条明细")
                parts.any { it.cents <= 0L } -> AppError.Custom("每条明细的金额都要大于 0")
                parts.sumOf { it.cents } != source.amount ->
                    AppError.Custom("明细合计必须等于原金额")
                else -> null
            }

        /** 账户校验：必须存在且可付款。 */
        private suspend fun validateAccounts(parts: List<SplitPart>): AppError? {
            for (part in parts) {
                // 同一账户允许重复出现：两笔现金合成一行是用户的自由，不强制合并
                val account = accountRepository.byId(part.accountId)
                if (account == null) return AppError.Custom("账户不存在")
                if (!FundPayable.canPay(account)) return AppError.Custom("${account.name} 不能用于付款")
            }
            return null
        }

        private suspend fun write(
            source: Txn,
            parts: List<SplitPart>,
            now: Long,
        ): AppResult<SplitResult> {
            val created = mutableListOf<String>()
            parts.forEachIndexed { index, part ->
                val txn = buildPart(source, part, index + 1, parts.size, now)
                runCatching { txnRepository.insert(txn) }.onFailure {
                    // 中途失败就把已插入的撤掉，别留下半笔账
                    created.forEach { runCatching { txnRepository.hardDelete(it) } }
                    return AppResult.Failure(AppError.Custom("拆分失败，请重试"))
                }
                created += txn.id
            }
            txnRepository.softDelete(source.id, now)
            return AppResult.Success(SplitResult(created, source.id))
        }

        /** 单条新流水：除金额与账户外，其余字段原样继承，备注标记序号便于日后追溯。 */
        private fun buildPart(
            source: Txn,
            part: SplitPart,
            index: Int,
            total: Int,
            now: Long,
        ): Txn =
            source.copy(
                id = Ids.newId("txn"),
                amount = part.cents,
                accountId = part.accountId,
                note = "${source.note}（拆分 $index/$total）".trim().take(200),
                createdAt = now,
                updatedAt = now,
                deletedAt = null,
                dateKey = DateUtils.dateKeyOf(source.dateTime),
            )

        companion object {
            const val MIN_PARTS = 2
            const val MAX_PARTS = 20
        }
    }
