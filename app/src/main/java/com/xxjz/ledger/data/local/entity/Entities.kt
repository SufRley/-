package com.xxjz.ledger.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 账目表，全系统核心。
 *
 * 索引策略（见 DEV_PLAN M1 关键技术点）：
 * `(dateKey)`、`(accountId, dateKey)`、`(categoryId, dateKey)`、`(deletedAt)`，
 * 另外加 `refundOfTxnId` 与 `debtId` 用于退款累计与借贷还款聚合。
 */
@Entity(
    tableName = "txn",
    indices = [
        Index("dateKey"),
        Index("accountId", "dateKey"),
        Index("categoryId", "dateKey"),
        Index("deletedAt"),
        Index("refundOfTxnId"),
        Index("debtId"),
    ],
)
data class TxnEntity(
    @PrimaryKey val id: String,
    val type: String,
    /** 金额（分），始终为正。 */
    val amount: Long,
    @ColumnInfo(name = "categoryId") val categoryId: String?,
    @ColumnInfo(name = "accountId") val accountId: String?,
    @ColumnInfo(name = "toAccountId") val toAccountId: String?,
    val fee: Long,
    @ColumnInfo(name = "dateTime") val dateTime: Long,
    @ColumnInfo(name = "dateKey") val dateKey: Int,
    val note: String,
    val memo: String,
    @ColumnInfo(name = "merchantId") val merchantId: String?,
    @ColumnInfo(name = "merchantName") val merchantName: String?,
    val currency: String,
    @ColumnInfo(name = "ledgerId") val ledgerId: String?,
    @ColumnInfo(name = "excludedFromStats") val excludedFromStats: Boolean,
    @ColumnInfo(name = "pendingReimbursement") val pendingReimbursement: Boolean,
    @ColumnInfo(name = "reimbursedAmount") val reimbursedAmount: Long,
    @ColumnInfo(name = "reimbursedAt") val reimbursedAt: Long?,
    @ColumnInfo(name = "refundOfTxnId") val refundOfTxnId: String?,
    @ColumnInfo(name = "parentTxnId") val parentTxnId: String?,
    @ColumnInfo(name = "debtId") val debtId: String?,
    /** 借贷动作（v1.9.0）：LEND_OUT / COLLECT / BORROW_IN / REPAY / WRITE_OFF，仅 DEBT 流水有值。 */
    @ColumnInfo(name = "debtAction") val debtAction: String? = null,
    /** 分期计划 id（v1.9.0）：这笔还款冲抵的是哪个分期，普通账单还款为空。 */
    @ColumnInfo(name = "installmentId") val installmentId: String? = null,
    /** 收据照片路径（v1.9.1），空表示没有附图。 */
    @ColumnInfo(name = "photoPath") val photoPath: String? = null,
    @ColumnInfo(name = "deletedAt") val deletedAt: Long?,
    @ColumnInfo(name = "createdAt") val createdAt: Long,
    @ColumnInfo(name = "updatedAt") val updatedAt: Long,
)

@Entity(tableName = "account", indices = [Index("deletedAt")])
data class AccountEntity(
    @PrimaryKey val id: String,
    val name: String,
    val type: String,
    @ColumnInfo(name = "initialBalance") val initialBalance: Long,
    val icon: String,
    val color: String,
    @ColumnInfo(name = "cardLimit") val cardLimit: Long?,
    @ColumnInfo(name = "billDay") val billDay: Int?,
    @ColumnInfo(name = "repayDay") val repayDay: Int?,
    val hidden: Boolean,
    @ColumnInfo(name = "sortOrder") val sortOrder: Int,
    /** 软删除时间，非空表示在回收站（M8）。 */
    @ColumnInfo(name = "deletedAt") val deletedAt: Long? = null,
    /** 投资账户累计成本（分），v1.8.0 新增，仅 INVEST 使用。 */
    @ColumnInfo(name = "costCents") val costCents: Long = 0L,
    /** 投资账户当前市值（分），v1.8.0 新增，仅 INVEST 使用。 */
    @ColumnInfo(name = "marketValueCents") val marketValueCents: Long = 0L,
    /** 投资账户市值是否计入净资产，v1.8.0 新增，默认计入。 */
    @ColumnInfo(name = "includeInNetAssets") val includeInNetAssets: Boolean = true,
    /** 借贷方向（v1.9.0）：LEND / BORROW，仅 DEBT 账户有值。 */
    @ColumnInfo(name = "debtDirection") val debtDirection: String? = null,
    /** 备注（v1.9.0），借贷账户承接旧台账的备注。 */
    val note: String = "",
    /** 借贷约定还款日（v1.9.0），仅 DEBT 账户使用。 */
    @ColumnInfo(name = "dueAt") val dueAt: Long? = null,
)

@Entity(tableName = "category", indices = [Index("parentId"), Index("type", "sortOrder")])
data class CategoryEntity(
    @PrimaryKey val id: String,
    val name: String,
    val parentId: String?,
    val type: String,
    val icon: String,
    val color: String,
    val sortOrder: Int,
    val hidden: Boolean,
    val pinned: Boolean,
    val usageCount: Int,
)

@Entity(tableName = "tag")
data class TagEntity(
    @PrimaryKey val id: String,
    val name: String,
    val color: String,
    val usageCount: Int,
)

@Entity(
    tableName = "txn_tag",
    primaryKeys = ["txnId", "tagId"],
    indices = [Index("tagId")],
)
data class TxnTagEntity(
    @ColumnInfo(name = "txnId") val txnId: String,
    @ColumnInfo(name = "tagId") val tagId: String,
)

@Entity(tableName = "merchant")
data class MerchantEntity(
    @PrimaryKey val id: String,
    val name: String,
    val usageCount: Int,
)

@Entity(tableName = "template")
data class TemplateEntity(
    @PrimaryKey val id: String,
    val name: String,
    val type: String,
    val amount: Long,
    @ColumnInfo(name = "categoryId") val categoryId: String?,
    @ColumnInfo(name = "accountId") val accountId: String?,
    @ColumnInfo(name = "toAccountId") val toAccountId: String?,
    val note: String,
    @ColumnInfo(name = "sortOrder") val sortOrder: Int,
)

/**
 * 预算表（M6）。
 *
 * 语义：`id` 是「这份预算」的标识，跨月不变；`monthKey` 是这一版配置生效的起始月份。
 * 读取某个月时，若该月没有任何行（含已软删行），就沿用此前最近一个有配置的月份，
 * 因此不需要每月复制行；用户在某月改动时，才把上月配置复制成本月版本（见 `BudgetMonth`）。
 */
@Entity(tableName = "budget", primaryKeys = ["id", "monthKey"], indices = [Index(value = ["monthKey"]), Index(value = ["deletedAt"])])
data class BudgetEntity(
    val id: String,
    @ColumnInfo(name = "monthKey") val monthKey: Int,
    val name: String,
    /** MONTH / YEAR。 */
    val period: String,
    /** TOTAL / CATEGORY。 */
    val scope: String,
    /** 逗号分隔的分类 id，TOTAL 时为空串。 */
    @ColumnInfo(name = "categoryIds") val categoryIds: String,
    val amount: Long,
    val enabled: Boolean,
    /** 软删除时间，非空表示在回收站。 */
    @ColumnInfo(name = "deletedAt") val deletedAt: Long?,
    @ColumnInfo(name = "sortOrder") val sortOrder: Int,
)

@Entity(tableName = "recurring_rule")
data class RecurringRuleEntity(
    @PrimaryKey val id: String,
    val type: String,
    val amount: Long,
    @ColumnInfo(name = "categoryId") val categoryId: String?,
    @ColumnInfo(name = "accountId") val accountId: String?,
    @ColumnInfo(name = "toAccountId") val toAccountId: String?,
    val note: String,
    val period: String,
    val interval: Int,
    @ColumnInfo(name = "nextRunAt") val nextRunAt: Long,
    @ColumnInfo(name = "endAt") val endAt: Long?,
    @ColumnInfo(name = "maxTimes") val maxTimes: Int?,
    @ColumnInfo(name = "runTimes") val runTimes: Int,
    val paused: Boolean,
)

@Entity(tableName = "debt")
data class DebtEntity(
    @PrimaryKey val id: String,
    val direction: String,
    val counterparty: String,
    val principal: Long,
    @ColumnInfo(name = "accountId") val accountId: String?,
    @ColumnInfo(name = "startAt") val startAt: Long,
    @ColumnInfo(name = "dueAt") val dueAt: Long?,
    val note: String,
    val settled: Boolean,
    /** 坏账：确认收不回来，台账结清但标记坏账（AC-65）。 */
    @ColumnInfo(name = "writtenOff") val writtenOff: Boolean = false,
)

/** 多账本预留表，v1 只存在一条默认账本记录。 */
@Entity(tableName = "ledger")
data class LedgerEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "isDefault") val isDefault: Boolean,
)

/**
 * 分期计划（v1.9.0 / PRD 3.2）。
 *
 * 刻意独立成表而不是给 `txn` 加列：分期信息（本金、期数、手续费、摊法）是一组独立数据，
 * 塞进 `txn` 会迫使 `type` 多出一个「这不是一笔账」的取值，污染全部统计 SQL 与 `when (type)` 分支。
 *
 * 「已还几期」不落库，由还款流水实时统计，避免两处数据各算。
 */
@Entity(tableName = "installment", indices = [Index("accountId"), Index("txnId")])
data class InstallmentEntity(
    @PrimaryKey val id: String,
    /** TXN（单笔消费）| BILL（整期账单）。 */
    val scope: String,
    @ColumnInfo(name = "txnId") val txnId: String?,
    @ColumnInfo(name = "accountId") val accountId: String,
    /** 分期本金（分）。 */
    @ColumnInfo(name = "principalCents") val principalCents: Long,
    /** 手续费总额（分），可为 0。 */
    @ColumnInfo(name = "feeCents") val feeCents: Long,
    /** AVERAGE（均摊）| FIRST（首期一次收齐）。 */
    @ColumnInfo(name = "feeMode") val feeMode: String,
    /** 期数，用户输入，2 ~ 60。 */
    val periods: Int,
    /** 起始账期 yyyyMM。 */
    @ColumnInfo(name = "startBillKey") val startBillKey: Int,
    @ColumnInfo(name = "createdAt") val createdAt: Long,
)
