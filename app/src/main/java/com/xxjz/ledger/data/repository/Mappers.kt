package com.xxjz.ledger.data.repository

import com.xxjz.ledger.data.local.entity.AccountEntity
import com.xxjz.ledger.data.local.entity.BudgetEntity
import com.xxjz.ledger.data.local.entity.CategoryEntity
import com.xxjz.ledger.data.local.entity.InstallmentEntity
import com.xxjz.ledger.data.local.entity.RecurringRuleEntity
import com.xxjz.ledger.data.local.entity.TagEntity
import com.xxjz.ledger.data.local.entity.TemplateEntity
import com.xxjz.ledger.data.local.entity.TxnEntity
import com.xxjz.ledger.domain.model.Account
import com.xxjz.ledger.domain.model.AccountType
import com.xxjz.ledger.domain.model.Budget
import com.xxjz.ledger.domain.model.BudgetPeriod
import com.xxjz.ledger.domain.model.BudgetScope
import com.xxjz.ledger.domain.model.Category
import com.xxjz.ledger.domain.model.DebtDirection
import com.xxjz.ledger.domain.model.FeeMode
import com.xxjz.ledger.domain.model.Installment
import com.xxjz.ledger.domain.model.InstallmentScope
import com.xxjz.ledger.domain.model.RecurringPeriod
import com.xxjz.ledger.domain.model.RecurringRule
import com.xxjz.ledger.domain.model.Tag
import com.xxjz.ledger.domain.model.Template
import com.xxjz.ledger.domain.model.Txn
import com.xxjz.ledger.domain.model.TxnType
import com.xxjz.ledger.domain.model.parseAccountType
import com.xxjz.ledger.domain.model.parseDebtAction

// ---------- Entity ↔ Model ----------

internal fun TxnEntity.toModel(): Txn =
    Txn(
        id = id,
        type = TxnType.valueOf(type),
        amount = amount,
        categoryId = categoryId,
        accountId = accountId,
        toAccountId = toAccountId,
        fee = fee,
        dateTime = dateTime,
        dateKey = dateKey,
        note = note,
        memo = memo,
        merchantId = merchantId,
        merchantName = merchantName,
        currency = currency,
        ledgerId = ledgerId,
        excludedFromStats = excludedFromStats,
        pendingReimbursement = pendingReimbursement,
        reimbursedAmount = reimbursedAmount,
        reimbursedAt = reimbursedAt,
        refundOfTxnId = refundOfTxnId,
        parentTxnId = parentTxnId,
        debtId = debtId,
        debtAction = parseDebtAction(debtAction),
        // v1.9.1 顺手补上：此前 toModel 漏了 installmentId，分期标签读出来恒为 null
        installmentId = installmentId,
        photoPath = photoPath,
        deletedAt = deletedAt,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

internal fun Txn.toEntity(): TxnEntity =
    TxnEntity(
        id = id,
        type = type.name,
        amount = amount,
        categoryId = categoryId,
        accountId = accountId,
        toAccountId = toAccountId,
        fee = fee,
        dateTime = dateTime,
        dateKey = dateKey,
        note = note,
        memo = memo,
        merchantId = merchantId,
        merchantName = merchantName,
        currency = currency,
        ledgerId = ledgerId,
        excludedFromStats = excludedFromStats,
        pendingReimbursement = pendingReimbursement,
        reimbursedAmount = reimbursedAmount,
        reimbursedAt = reimbursedAt,
        refundOfTxnId = refundOfTxnId,
        parentTxnId = parentTxnId,
        debtId = debtId,
        debtAction = debtAction?.name,
        installmentId = installmentId,
        photoPath = photoPath,
        deletedAt = deletedAt,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

internal fun AccountEntity.toModel(balance: Long = 0): Account =
    Account(
        id = id,
        name = name,
        // v1.8.0：老库/老备份里的 FUND 一律解析为 SAVINGS，未知值兜底 SAVINGS 而非抛异常
        type = parseAccountType(type),
        initialBalance = initialBalance,
        icon = icon,
        color = color,
        cardLimit = cardLimit,
        billDay = billDay,
        repayDay = repayDay,
        hidden = hidden,
        sortOrder = sortOrder,
        balance = balance,
        costCents = costCents,
        marketValueCents = marketValueCents,
        includeInNetAssets = includeInNetAssets,
        debtDirection = parseDebtDirection(debtDirection),
        note = note,
        dueAt = dueAt,
    )

/** 借贷方向容错解析：历史账户没有这一列，按「我借出」处理（余额为正时显示为待收更符合直觉）。 */
internal fun parseDebtDirection(raw: String?): DebtDirection =
    if (raw?.trim()?.uppercase() == "BORROW") DebtDirection.BORROW else DebtDirection.LEND

internal fun Account.toEntity(): AccountEntity =
    AccountEntity(
        id = id,
        name = name,
        type = type.name,
        initialBalance = initialBalance,
        icon = icon,
        color = color,
        cardLimit = cardLimit,
        billDay = billDay,
        repayDay = repayDay,
        hidden = hidden,
        sortOrder = sortOrder,
        costCents = costCents,
        marketValueCents = marketValueCents,
        includeInNetAssets = includeInNetAssets,
        debtDirection = if (type == AccountType.DEBT) (debtDirection ?: DebtDirection.LEND).name else null,
        note = note,
        dueAt = dueAt,
    )

internal fun CategoryEntity.toModel(): Category =
    Category(
        id = id,
        name = name,
        parentId = parentId,
        type = TxnType.valueOf(type),
        icon = icon,
        color = color,
        sortOrder = sortOrder,
        hidden = hidden,
        pinned = pinned,
        usageCount = usageCount,
    )

internal fun Category.toEntity(): CategoryEntity =
    CategoryEntity(
        id = id,
        name = name,
        parentId = parentId,
        type = type.name,
        icon = icon,
        color = color,
        sortOrder = sortOrder,
        hidden = hidden,
        pinned = pinned,
        usageCount = usageCount,
    )

internal fun TagEntity.toModel(): Tag = Tag(id = id, name = name, color = color, usageCount = usageCount)

internal fun TemplateEntity.toModel(): Template =
    Template(
        id = id,
        name = name,
        type = TxnType.valueOf(type),
        amount = amount,
        categoryId = categoryId,
        accountId = accountId,
        toAccountId = toAccountId,
        note = note,
        sortOrder = sortOrder,
    )

internal fun Template.toEntity(): TemplateEntity =
    TemplateEntity(
        id = id,
        name = name,
        type = type.name,
        amount = amount,
        categoryId = categoryId,
        accountId = accountId,
        toAccountId = toAccountId,
        note = note,
        sortOrder = sortOrder,
    )

internal fun BudgetEntity.toModel(): Budget =
    Budget(
        id = id,
        monthKey = monthKey,
        name = name,
        period = BudgetPeriod.valueOf(period),
        scope = BudgetScope.valueOf(scope),
        categoryIds = categoryIds.split(BUDGET_CATEGORY_SEP).filter { it.isNotBlank() },
        amount = amount,
        enabled = enabled,
        deletedAt = deletedAt,
        sortOrder = sortOrder,
    )

internal fun Budget.toEntity(): BudgetEntity =
    BudgetEntity(
        id = id,
        monthKey = monthKey,
        name = name,
        period = period.name,
        scope = scope.name,
        categoryIds = categoryIds.filter { it.isNotBlank() }.joinToString(BUDGET_CATEGORY_SEP),
        amount = amount,
        enabled = enabled,
        deletedAt = deletedAt,
        sortOrder = sortOrder,
    )

/** 预算分类 id 列表的存储分隔符。 */
private const val BUDGET_CATEGORY_SEP = ","

internal fun RecurringRuleEntity.toModel(): RecurringRule =
    RecurringRule(
        id = id,
        type = TxnType.valueOf(type),
        amount = amount,
        categoryId = categoryId,
        accountId = accountId,
        toAccountId = toAccountId,
        note = note,
        period = RecurringPeriod.valueOf(period),
        interval = interval,
        nextRunAt = nextRunAt,
        endAt = endAt,
        maxTimes = maxTimes,
        runTimes = runTimes,
        paused = paused,
    )

internal fun RecurringRule.toEntity(): RecurringRuleEntity =
    RecurringRuleEntity(
        id = id,
        type = type.name,
        amount = amount,
        categoryId = categoryId,
        accountId = accountId,
        toAccountId = toAccountId,
        note = note,
        period = period.name,
        interval = interval,
        nextRunAt = nextRunAt,
        endAt = endAt,
        maxTimes = maxTimes,
        runTimes = runTimes,
        paused = paused,
    )

internal fun InstallmentEntity.toModel(): Installment =
    Installment(
        id = id,
        scope = if (scope == "BILL") InstallmentScope.BILL else InstallmentScope.TXN,
        txnId = txnId,
        accountId = accountId,
        principalCents = principalCents,
        feeCents = feeCents,
        feeMode = if (feeMode == "FIRST") FeeMode.FIRST else FeeMode.AVERAGE,
        periods = periods,
        startBillKey = startBillKey,
        createdAt = createdAt,
        paidPeriods = paidPeriods,
        nextRunAt = nextRunAt,
    )

internal fun Installment.toEntity(): InstallmentEntity =
    InstallmentEntity(
        id = id,
        scope = scope.name,
        txnId = txnId,
        accountId = accountId,
        principalCents = principalCents,
        feeCents = feeCents,
        feeMode = feeMode.name,
        periods = periods,
        startBillKey = startBillKey,
        createdAt = createdAt,
        paidPeriods = paidPeriods,
        nextRunAt = nextRunAt,
    )
