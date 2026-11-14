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
 * 借贷账户草稿（v1.9.0 / PRD 2.5）。
 *
 * 一个借贷账户 = 一个人，方向在建立时锁定，之后只显示对应的两个操作。
 */
data class DebtAccountDraft(
    val id: String? = null,
    val counterparty: String,
    val direction: DebtDirection = DebtDirection.LEND,
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
                    debtDirection = draft.direction,
                    note = draft.note.trim(),
                    dueAt = draft.dueAt,
                )
            accountRepository.upsert(account)

            // 首次本金：借出 → LEND_OUT，借入 → BORROW_IN
            if (draft.principalCents > 0L && draft.fundAccountId != null) {
                val result =
                    recordDebtOp(
                        DebtOpDraft(
                            action = if (draft.direction == DebtDirection.LEND) DebtAction.LEND_OUT else DebtAction.BORROW_IN,
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
         */
        private fun validate(
            draft: DebtOpDraft,
            debt: Account,
            accounts: List<Account>,
        ): AppError? {
            if (draft.amountCents <= 0L) return AppError.AmountNotPositive
            if (draft.amountCents > MoneyFormatter.MAX_CENTS) return AppError.AmountTooLarge
            if (debt.type != AccountType.DEBT) return AppError.Custom("该账户不是借贷账户")

            // 方向锁定：我借出的账户只能借出 / 收款，我借入的只能借入 / 还款
            val needLending = draft.action == DebtAction.LEND_OUT || draft.action == DebtAction.COLLECT
            if (needLending && !debt.isLending) {
                return AppError.Custom("「${debt.name}」是「我借入」账户，只能记借入和还款")
            }
            if (!needLending && draft.action != DebtAction.WRITE_OFF && !debt.isBorrowing) {
                return AppError.Custom("「${debt.name}」是「我借出」账户，只能记借出和收款")
            }

            if (draft.action == DebtAction.WRITE_OFF) return validateWriteOff(draft, debt)
            return validateWithFund(draft, debt, accounts)
        }

        private fun validateWriteOff(
            draft: DebtOpDraft,
            debt: Account,
        ): AppError? {
            if (!debt.isLending) return AppError.Custom("只有「我借出」的账户可以做坏账核销")
            if (debt.balance <= 0L) return AppError.Custom("该账户没有待收金额")
            if (draft.amountCents > debt.balance) return AppError.Custom("核销金额不能超过待收金额")
            return null
        }

        /** 有资金账户的三类操作：资金账户必须真实存在，且收款/还款不能超过余额。 */
        private fun validateWithFund(
            draft: DebtOpDraft,
            debt: Account,
            accounts: List<Account>,
        ): AppError? {
            val fund = accounts.firstOrNull { it.id == draft.fundAccountId } ?: return AppError.AccountRequired
            if (fund.id == debt.id || fund.type == AccountType.DEBT) return AppError.Custom("资金账户不能是借贷账户")
            // 收款不能超过待收、还款不能超过待还：否则余额会反向，语义就乱了
            return when (draft.action) {
                DebtAction.COLLECT ->
                    if (draft.amountCents > debt.balance) {
                        AppError.Custom(
                            "收款金额不能超过待收 ${MoneyFormatter.centsToYuan(debt.balance, showSymbol = true)}",
                        )
                    } else {
                        null
                    }
                DebtAction.REPAY ->
                    if (draft.amountCents > -debt.balance) {
                        AppError.Custom(
                            "还款金额不能超过待还 ${MoneyFormatter.centsToYuan(-debt.balance, showSymbol = true)}",
                        )
                    } else {
                        null
                    }
                else -> null
            }
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
 * 只对「我借出」账户开放：「我借入」方向核销等于凭空虚增净资产。
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
            if (!account.isLending) return AppResult.Failure(AppError.Custom("只有「我借出」的账户可以做坏账核销"))
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
