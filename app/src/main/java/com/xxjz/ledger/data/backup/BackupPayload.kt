package com.xxjz.ledger.data.backup

import kotlinx.serialization.Serializable

/**
 * 备份载荷（AC-68）。
 *
 * 设计要点：
 * - **与 Room 实体解耦**：这里是一份独立的 wire format，字段全部带默认值。以后给表加列，
 *   老备份照样能反序列化（缺的字段取默认），新版 App 导出的备份在旧版 App 里也能识别出
 *   `schemaVersion` 过高并给出明确提示，而不是解析崩溃。
 * - [schemaVersion] 是导出时的数据库版本；[payloadVersion] 是备份格式自身的版本，两者独立。
 * - 只装数据，不装密码、不装手势锁哈希等敏感凭据。
 */
@Serializable
data class BackupPayload(
    val payloadVersion: Int = CURRENT_PAYLOAD_VERSION,
    /** 导出时的 Room 版本。 */
    val schemaVersion: Int = 0,
    val appVersion: String = "",
    /** 导出时间（毫秒）。 */
    val createdAt: Long = 0,
    val settings: BackupSettings = BackupSettings(),
    val txns: List<TxnDto> = emptyList(),
    val accounts: List<AccountDto> = emptyList(),
    val categories: List<CategoryDto> = emptyList(),
    val tags: List<TagDto> = emptyList(),
    val txnTags: List<TxnTagDto> = emptyList(),
    val merchants: List<MerchantDto> = emptyList(),
    val templates: List<TemplateDto> = emptyList(),
    val budgets: List<BudgetDto> = emptyList(),
    val recurringRules: List<RecurringRuleDto> = emptyList(),
    val debts: List<DebtDto> = emptyList(),
    val ledgers: List<LedgerDto> = emptyList(),
) {
    /** 导入前给用户看的统计（AC-71）。 */
    val stats: BackupStats
        get() =
            BackupStats(
                txnCount = txns.count { it.deletedAt == null },
                accountCount = accounts.count { it.deletedAt == null },
                categoryCount = categories.size,
                createdAt = createdAt,
            )

    companion object {
        /** 备份格式版本：结构不兼容时才 +1。 */
        const val CURRENT_PAYLOAD_VERSION = 1

        /** 能导入的最低格式版本。 */
        const val MIN_SUPPORTED_PAYLOAD_VERSION = 1
    }
}

/** 导入预览统计。 */
data class BackupStats(
    val txnCount: Int,
    val accountCount: Int,
    val categoryCount: Int,
    val createdAt: Long,
)

/** 设置快照。刻意不含手势锁哈希，避免把解锁凭据跟着备份文件到处走。 */
@Serializable
data class BackupSettings(
    val defaultAccountId: String? = null,
    val defaultTimeMode: String = "NOW",
    val fixedTimeMinutes: Int = 540,
    val decimalPlaces: Int = 2,
    val keypadType: String = "CALC",
    val amountColorsReversed: Boolean = false,
    val themeMode: String = "SYSTEM",
    val recycleBinDays: Int = 30,
    val backupReminderEnabled: Boolean = true,
    val lastTxnType: String = "EXPENSE",
    val savingGoalCents: Long = 0,
)

@Serializable
data class TxnDto(
    val id: String = "",
    val type: String = "EXPENSE",
    val amount: Long = 0,
    val categoryId: String? = null,
    val accountId: String? = null,
    val toAccountId: String? = null,
    val fee: Long = 0,
    val dateTime: Long = 0,
    val dateKey: Int = 0,
    val note: String = "",
    val memo: String = "",
    val merchantId: String? = null,
    val merchantName: String? = null,
    val currency: String = "CNY",
    val ledgerId: String? = null,
    val excludedFromStats: Boolean = false,
    val pendingReimbursement: Boolean = false,
    val reimbursedAmount: Long = 0,
    val reimbursedAt: Long? = null,
    val refundOfTxnId: String? = null,
    val parentTxnId: String? = null,
    val debtId: String? = null,
    val deletedAt: Long? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
)

@Serializable
data class AccountDto(
    val id: String = "",
    val name: String = "",
    val type: String = "CASH",
    val initialBalance: Long = 0,
    val icon: String = "",
    val color: String = "",
    val cardLimit: Long? = null,
    val billDay: Int? = null,
    val repayDay: Int? = null,
    val hidden: Boolean = false,
    val sortOrder: Int = 0,
    val deletedAt: Long? = null,
    /** 投资累计成本（分），v1.8.0 新增；老备份无此字段时为 0。 */
    val costCents: Long = 0,
    /** 投资当前市值（分），v1.8.0 新增；老备份无此字段时为 0。 */
    val marketValueCents: Long = 0,
    /** 投资市值是否计入净资产，v1.8.0 新增；老备份无此字段时默认计入。 */
    val includeInNetAssets: Boolean = true,
)

@Serializable
data class CategoryDto(
    val id: String = "",
    val name: String = "",
    val parentId: String? = null,
    val type: String = "EXPENSE",
    val icon: String = "",
    val color: String = "",
    val sortOrder: Int = 0,
    val hidden: Boolean = false,
    val pinned: Boolean = false,
    val usageCount: Int = 0,
)

@Serializable
data class TagDto(
    val id: String = "",
    val name: String = "",
    val color: String = "",
    val usageCount: Int = 0,
)

@Serializable
data class TxnTagDto(
    val txnId: String = "",
    val tagId: String = "",
)

@Serializable
data class MerchantDto(
    val id: String = "",
    val name: String = "",
    val usageCount: Int = 0,
)

@Serializable
data class TemplateDto(
    val id: String = "",
    val name: String = "",
    val type: String = "EXPENSE",
    val amount: Long = 0,
    val categoryId: String? = null,
    val accountId: String? = null,
    val toAccountId: String? = null,
    val note: String = "",
    val sortOrder: Int = 0,
)

@Serializable
data class BudgetDto(
    val id: String = "",
    val monthKey: Int = 0,
    val name: String = "",
    val period: String = "MONTH",
    val scope: String = "TOTAL",
    val categoryIds: String = "",
    val amount: Long = 0,
    val enabled: Boolean = true,
    val deletedAt: Long? = null,
    val sortOrder: Int = 0,
)

@Serializable
data class RecurringRuleDto(
    val id: String = "",
    val type: String = "EXPENSE",
    val amount: Long = 0,
    val categoryId: String? = null,
    val accountId: String? = null,
    val toAccountId: String? = null,
    val note: String = "",
    val period: String = "MONTH",
    val interval: Int = 1,
    val nextRunAt: Long = 0,
    val endAt: Long? = null,
    val maxTimes: Int? = null,
    val runTimes: Int = 0,
    val paused: Boolean = false,
)

@Serializable
data class DebtDto(
    val id: String = "",
    val direction: String = "LEND",
    val counterparty: String = "",
    val principal: Long = 0,
    val accountId: String? = null,
    val startAt: Long = 0,
    val dueAt: Long? = null,
    val note: String = "",
    val settled: Boolean = false,
    val writtenOff: Boolean = false,
)

@Serializable
data class LedgerDto(
    val id: String = "",
    val name: String = "",
    val isDefault: Boolean = false,
)
