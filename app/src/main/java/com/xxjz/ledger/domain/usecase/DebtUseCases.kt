package com.xxjz.ledger.domain.usecase

import com.xxjz.ledger.core.common.AppError
import com.xxjz.ledger.core.common.AppResult
import com.xxjz.ledger.core.common.DateUtils
import com.xxjz.ledger.core.common.MoneyFormatter
import com.xxjz.ledger.data.local.seed.SeedData
import com.xxjz.ledger.domain.model.Account
import com.xxjz.ledger.domain.model.AccountType
import com.xxjz.ledger.domain.model.DebtAction
import com.xxjz.ledger.domain.model.DebtDirection
import com.xxjz.ledger.domain.model.Txn
import com.xxjz.ledger.domain.model.TxnType
import com.xxjz.ledger.domain.repository.AccountRepository
import com.xxjz.ledger.domain.repository.TxnRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * 借贷账户草稿（v1.9.0 / PRD 3）。
 *
 * 一个借贷账户 = 一个人 = 一本流水账。不再有「方向」，方向由余额符号表达：
 * 正数待收、负数待还，随时可在同一账户上借出 / 收款 / 借入 / 还款。
 */
data class DebtAccountDraft(
    val id: String? = null,
    val counterparty: String,
    /** 建账户时顺手记的第一笔动作；本金 > 0 时必填。 */
    val firstAction: DebtAction = DebtAction.LEND_OUT,
    /** 首次借出 / 借入的本金（分）。0 表示先建人、之后单独记账。 */
    val principalCents: Long = 0,
    /** 本金从哪个资金账户进出；本金 > 0 时必填。 */
    val fundAccountId: String? = null,
    val dueAt: Long? = null,
    val note: String = "",
)

/** 新建 / 编辑借贷账户。 */
class SaveDebtAccountUseCase
    @Inject
    constructor(
        private val accountRepository: AccountRepository,
        private val recordDebtOp: RecordDebtOpUseCase,
    ) {
        suspend operator fun invoke(draft: DebtAccountDraft): AppResult<String> {
            val name = draft.counterparty.trim()
            val error =
                when {
                    name.isEmpty() -> AppError.DebtCounterpartyRequired
                    draft.principalCents < 0L -> AppError.AmountNotPositive
                    draft.principalCents > MoneyFormatter.MAX_CENTS -> AppError.AmountTooLarge
                    draft.principalCents > 0L && draft.fundAccountId == null ->
                        AppError.Custom("请选择本金从哪个账户进出")
                    draft.firstAction != DebtAction.LEND_OUT && draft.firstAction != DebtAction.BORROW_IN ->
                        AppError.Custom("首笔只能是借出或借入")
                    else -> null
                }
            if (error != null) return AppResult.Failure(error)

            val existing = draft.id?.let { accountRepository.byId(it) }
            if (draft.id != null && existing == null) return AppResult.Failure(AppError.NotFound)

            val account =
                Account(
                    id = existing?.id ?: SeedData.newId("acc"),
                    name = name,
                    type = AccountType.DEBT,
                    // 新建账户的初始余额恒为 0：本金走流水，这样资金侧同时减少，
                    // 净资产才不会被「凭空出现的应收」抬高。
                    initialBalance = existing?.initialBalance ?: 0L,
                    icon = existing?.icon.orEmpty(),
                    color = existing?.color.orEmpty(),
                    sortOrder = existing?.sortOrder ?: 0,
                    hidden = existing?.hidden ?: false,
                    // v1.9.0 净值模型：该字段仅为历史数据兼容保留，不再参与任何逻辑
                    debtDirection = existing?.debtDirection ?: DebtDirection.LEND,
                    note = draft.note.trim(),
                    dueAt = draft.dueAt,
                )
            accountRepository.upsert(account)

            // 首笔本金：借出 → LEND_OUT，借入 → BORROW_IN
            if (draft.principalCents > 0L && draft.fundAccountId != null) {
                val result =
                    recordDebtOp(
                        DebtOpDraft(
                            action = draft.firstAction,
                            debtAccountId = account.id,
                            fundAccountId = draft.fundAccountId,
                            amountCents = draft.principalCents,
                            note = draft.note.trim(),
                        ),
                    )
                if (result is AppResult.Failure) return result
            }
            return AppResult.Success(account.id)
        }
    }

/** 一次借贷操作。 */
data class DebtOpDraft(
    val action: DebtAction,
    /** 借贷账户（对方）。 */
    val debtAccountId: String,
    /** 资金账户；坏账核销时为空。 */
    val fundAccountId: String? = null,
    val amountCents: Long,
    val at: Long = System.currentTimeMillis(),
    val note: String = "",
)

/**
 * 记一笔借贷操作（v1.9.0 / PRD 2.2）。
 *
 * 一条 `type = DEBT` 的流水同时落在两个账户上：`accountId` = 借贷账户、`toAccountId` = 资金账户。
 * 两侧符号相反，因此**任何一笔操作都不改变净资产**，这正是验收口径（借出 1000 后净资产仍是 10000）。
 * 借贷流水也不计入收支统计（`excludedFromStats = true`），月度收入支出数字不受污染。
 *
 * **v1.9.0 修订（PRD 第 3 条）：取消方向锁定。** 一个借贷账户就是一个人的往来账，
 * 四种操作任意账户都能记，余额是净值——正数是待收、负数是待还，符号会随操作自然翻转。
 * 因此收款/还款也**不再做上限校验**：跟 A 的账就是一本流水账，收多了就是「我欠他」。
 */
class RecordDebtOpUseCase
    @Inject
    constructor(
        private val accountRepository: AccountRepository,
        private val txnRepository: TxnRepository,
    ) {
        suspend operator fun invoke(draft: DebtOpDraft): AppResult<String> {
            val accounts = accountRepository.observeWithBalances().first()
            val debt = accounts.firstOrNull { it.id == draft.debtAccountId } ?: return AppResult.Failure(AppError.NotFound)
            validate(draft, debt, accounts)?.let { return AppResult.Failure(it) }

            val actionText = ACTION_LABELS.getValue(draft.action)
            val now = System.currentTimeMillis()
            val txn =
                Txn(
                    id = SeedData.newId("txn"),
                    type = TxnType.DEBT,
                    amount = draft.amountCents,
                    categoryId = null,
                    accountId = debt.id,
                    toAccountId = if (draft.action == DebtAction.WRITE_OFF) null else draft.fundAccountId,
                    dateTime = draft.at,
                    dateKey = DateUtils.dateKeyOf(draft.at),
                    note = draft.note.ifBlank { "$actionText：${debt.name}" }.take(200),
                    // 借贷不污染收支统计（PRD 2.2）
                    excludedFromStats = true,
                    debtAction = draft.action,
                    createdAt = now,
                    updatedAt = now,
                )
            txnRepository.insert(txn)
            return AppResult.Success(txn.id)
        }

        /**
         * 前置校验，返回 null 表示通过。
         *
         * 单独拆出来是因为原函数有 14 个 return，踩了 detekt 的 ReturnCount 阈值；
         * 拆开之后每条规则一行 if，读起来也比原来一长串 `if (x) return` 顺。
         *
         * v1.9.0 修订后只剩三类校验：金额、账户类型、资金账户合法性。
         * 方向与金额上限的校验都被**刻意移除**（PRD 第 3 条）。
         */
        private fun validate(
            draft: DebtOpDraft,
            debt: Account,
            accounts: List<Account>,
        ): AppError? {
            if (draft.amountCents <= 0L) return AppError.AmountNotPositive
            if (draft.amountCents > MoneyFormatter.MAX_CENTS) return AppError.AmountTooLarge
            if (debt.type != AccountType.DEBT) return AppError.Custom("该账户不是借贷账户")
            if (draft.action == DebtAction.WRITE_OFF) return validateWriteOff(draft, debt)
            return validateFund(draft, debt, accounts)
        }

        /**
         * 坏账核销：只对「有净待收」的账户开放。
         *
         * 净值模型下「待收」= 余额为正。余额为负（我欠对方）时核销等于凭空虚增净资产，必须挡住。
         */
        private fun validateWriteOff(
            draft: DebtOpDraft,
            debt: Account,
        ): AppError? {
            if (debt.balance <= 0L) return AppError.Custom("该账户没有待收金额")
            if (draft.amountCents > debt.balance) return AppError.Custom("核销金额不能超过待收金额")
            return null
        }

        /** 有资金账户的三类操作：资金账户必须真实存在且不是借贷账户。金额上限不再校验。 */
        private fun validateFund(
            draft: DebtOpDraft,
            debt: Account,
            accounts: List<Account>,
        ): AppError? {
            val fund = accounts.firstOrNull { it.id == draft.fundAccountId } ?: return AppError.AccountRequired
            if (fund.id == debt.id || fund.type == AccountType.DEBT) return AppError.Custom("资金账户不能是借贷账户")
            return null
        }

        companion object {
            val ACTION_LABELS: Map<DebtAction, String> =
                mapOf(
                    DebtAction.LEND_OUT to "借出",
                    DebtAction.COLLECT to "收款",
                    DebtAction.BORROW_IN to "借入",
                    DebtAction.REPAY to "还款",
                    DebtAction.WRITE_OFF to "坏账核销",
                )
        }
    }

/**
 * 坏账核销（PRD 2.2）：把待收余额一次性清零。
 *
 * 这是**唯一**会改变净资产的借贷操作——钱确实收不回来了，资产要减。
 * v1.9.0 净值模型：只对「待收（余额 > 0）」开放，与账户创建时选的方向无关；
 * 待还时核销等于凭空虚增净资产。
 */
class WriteOffDebtUseCase
    @Inject
    constructor(
        private val accountRepository: AccountRepository,
        private val recordDebtOp: RecordDebtOpUseCase,
    ) {
        suspend operator fun invoke(
            debtAccountId: String,
            at: Long = System.currentTimeMillis(),
        ): AppResult<String> {
            val account = accountRepository.byId(debtAccountId) ?: return AppResult.Failure(AppError.NotFound)
            val balance = accountRepository.balanceOf(debtAccountId)
            if (balance <= 0L) return AppResult.Failure(AppError.Custom("该账户没有待收金额"))
            return recordDebtOp(
                DebtOpDraft(
                    action = DebtAction.WRITE_OFF,
                    debtAccountId = debtAccountId,
                    amountCents = balance,
                    at = at,
                    note = "坏账核销：${account.name}",
                ),
            )
        }
    }

/**
 * 借贷账户合并（v1.9.0 / PRD 3.4）。
 *
 * 净值模型下「一个人一个账户」，但历史数据里同一个人可能被建成了「我借出」「我借入」两个账户。
 * 这里让用户手动把其中一个并到另一个：流水整体改挂 + 源账户进回收站，
 * 合并后余额是两边净值之和，符号自然体现谁欠谁。
 *
 * 刻意**不做自动合并**：同名不等于同一个人（真有两个「小王」），宁可让用户点一下。
 */
class MergeDebtAccountUseCase
    @Inject
    constructor(
        private val accountRepository: AccountRepository,
    ) {
        suspend operator fun invoke(
            sourceId: String,
            targetId: String,
            at: Long = System.currentTimeMillis(),
        ): AppResult<Long> {
            if (sourceId == targetId) return AppResult.Failure(AppError.Custom("不能合并到自己"))
            val source = accountRepository.byId(sourceId) ?: return AppResult.Failure(AppError.NotFound)
            val target = accountRepository.byId(targetId) ?: return AppResult.Failure(AppError.NotFound)
            if (source.type != AccountType.DEBT || target.type != AccountType.DEBT) {
                return AppResult.Failure(AppError.Custom("只能合并借贷账户"))
            }
            accountRepository.moveTxnsTo(sourceId, targetId, at)
            // 源账户的流水已经搬空，直接进回收站而不是物理删——给误操作留条后路
            accountRepository.deleteWithTxns(sourceId, at)
            return AppResult.Success(accountRepository.balanceOf(targetId))
        }
    }
