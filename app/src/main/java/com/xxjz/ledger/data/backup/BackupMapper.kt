package com.xxjz.ledger.data.backup

import com.xxjz.ledger.data.local.entity.AccountEntity
import com.xxjz.ledger.data.local.entity.BudgetEntity
import com.xxjz.ledger.data.local.entity.CategoryEntity
import com.xxjz.ledger.data.local.entity.DebtEntity
import com.xxjz.ledger.data.local.entity.LedgerEntity
import com.xxjz.ledger.data.local.entity.MerchantEntity
import com.xxjz.ledger.data.local.entity.RecurringRuleEntity
import com.xxjz.ledger.data.local.entity.TagEntity
import com.xxjz.ledger.data.local.entity.TemplateEntity
import com.xxjz.ledger.data.local.entity.TxnEntity
import com.xxjz.ledger.data.local.entity.TxnTagEntity
import com.xxjz.ledger.domain.model.parseAccountType

/**
 * 实体 ↔ 备份 DTO 的双向映射。
 *
 * 全部字段显式搬运，不用反射：字段增减时编译器会提醒，避免出现「备份少了一列但没人发现」。
 */
internal fun TxnEntity.toDto(): TxnDto =
    TxnDto(
        id = id,
        type = type,
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
        deletedAt = deletedAt,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

internal fun TxnDto.toEntity(): TxnEntity =
    TxnEntity(
        id = id,
        type = type,
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
        deletedAt = deletedAt,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

internal fun AccountEntity.toDto(): AccountDto =
    AccountDto(
        id = id,
        name = name,
        type = type,
        initialBalance = initialBalance,
        icon = icon,
        color = color,
        cardLimit = cardLimit,
        billDay = billDay,
        repayDay = repayDay,
        hidden = hidden,
        sortOrder = sortOrder,
        deletedAt = deletedAt,
        costCents = costCents,
        marketValueCents = marketValueCents,
        includeInNetAssets = includeInNetAssets,
    )

internal fun AccountDto.toEntity(): AccountEntity =
    AccountEntity(
        id = id,
        name = name,
        // v1.8.0：老备份里的 FUND（≤ v1.7.0 的唯一资金类）一律落成 SAVINGS，未知值同样兜底为 SAVINGS。
        type = parseAccountType(type).name,
        initialBalance = initialBalance,
        icon = icon,
        color = color,
        cardLimit = cardLimit,
        billDay = billDay,
        repayDay = repayDay,
        hidden = hidden,
        sortOrder = sortOrder,
        deletedAt = deletedAt,
        costCents = costCents,
        marketValueCents = marketValueCents,
        includeInNetAssets = includeInNetAssets,
    )

internal fun CategoryEntity.toDto(): CategoryDto =
    CategoryDto(
        id = id,
        name = name,
        parentId = parentId,
        type = type,
        icon = icon,
        color = color,
        sortOrder = sortOrder,
        hidden = hidden,
        pinned = pinned,
        usageCount = usageCount,
    )

internal fun CategoryDto.toEntity(): CategoryEntity =
    CategoryEntity(
        id = id,
        name = name,
        parentId = parentId,
        type = type,
        icon = icon,
        color = color,
        sortOrder = sortOrder,
        hidden = hidden,
        pinned = pinned,
        usageCount = usageCount,
    )

internal fun TagEntity.toDto(): TagDto = TagDto(id = id, name = name, color = color, usageCount = usageCount)

internal fun TagDto.toEntity(): TagEntity = TagEntity(id = id, name = name, color = color, usageCount = usageCount)

internal fun TxnTagEntity.toDto(): TxnTagDto = TxnTagDto(txnId = txnId, tagId = tagId)

internal fun TxnTagDto.toEntity(): TxnTagEntity = TxnTagEntity(txnId = txnId, tagId = tagId)

internal fun MerchantEntity.toDto(): MerchantDto = MerchantDto(id = id, name = name, usageCount = usageCount)

internal fun MerchantDto.toEntity(): MerchantEntity = MerchantEntity(id = id, name = name, usageCount = usageCount)

internal fun TemplateEntity.toDto(): TemplateDto =
    TemplateDto(
        id = id,
        name = name,
        type = type,
        amount = amount,
        categoryId = categoryId,
        accountId = accountId,
        toAccountId = toAccountId,
        note = note,
        sortOrder = sortOrder,
    )

internal fun TemplateDto.toEntity(): TemplateEntity =
    TemplateEntity(
        id = id,
        name = name,
        type = type,
        amount = amount,
        categoryId = categoryId,
        accountId = accountId,
        toAccountId = toAccountId,
        note = note,
        sortOrder = sortOrder,
    )

internal fun BudgetEntity.toDto(): BudgetDto =
    BudgetDto(
        id = id,
        monthKey = monthKey,
        name = name,
        period = period,
        scope = scope,
        categoryIds = categoryIds,
        amount = amount,
        enabled = enabled,
        deletedAt = deletedAt,
        sortOrder = sortOrder,
    )

internal fun BudgetDto.toEntity(): BudgetEntity =
    BudgetEntity(
        id = id,
        monthKey = monthKey,
        name = name,
        period = period,
        scope = scope,
        categoryIds = categoryIds,
        amount = amount,
        enabled = enabled,
        deletedAt = deletedAt,
        sortOrder = sortOrder,
    )

internal fun RecurringRuleEntity.toDto(): RecurringRuleDto =
    RecurringRuleDto(
        id = id,
        type = type,
        amount = amount,
        categoryId = categoryId,
        accountId = accountId,
        toAccountId = toAccountId,
        note = note,
        period = period,
        interval = interval,
        nextRunAt = nextRunAt,
        endAt = endAt,
        maxTimes = maxTimes,
        runTimes = runTimes,
        paused = paused,
    )

internal fun RecurringRuleDto.toEntity(): RecurringRuleEntity =
    RecurringRuleEntity(
        id = id,
        type = type,
        amount = amount,
        categoryId = categoryId,
        accountId = accountId,
        toAccountId = toAccountId,
        note = note,
        period = period,
        interval = interval,
        nextRunAt = nextRunAt,
        endAt = endAt,
        maxTimes = maxTimes,
        runTimes = runTimes,
        paused = paused,
    )

internal fun DebtEntity.toDto(): DebtDto =
    DebtDto(
        id = id,
        direction = direction,
        counterparty = counterparty,
        principal = principal,
        accountId = accountId,
        startAt = startAt,
        dueAt = dueAt,
        note = note,
        settled = settled,
        writtenOff = writtenOff,
    )

internal fun DebtDto.toEntity(): DebtEntity =
    DebtEntity(
        id = id,
        direction = direction,
        counterparty = counterparty,
        principal = principal,
        accountId = accountId,
        startAt = startAt,
        dueAt = dueAt,
        note = note,
        settled = settled,
        writtenOff = writtenOff,
    )

internal fun LedgerEntity.toDto(): LedgerDto = LedgerDto(id = id, name = name, isDefault = isDefault)

internal fun LedgerDto.toEntity(): LedgerEntity = LedgerEntity(id = id, name = name, isDefault = isDefault)
