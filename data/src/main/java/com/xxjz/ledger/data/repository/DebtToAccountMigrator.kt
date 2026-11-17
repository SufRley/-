package com.xxjz.ledger.data.repository

import androidx.room.withTransaction
import com.xxjz.ledger.core.common.DateUtils
import com.xxjz.ledger.core.common.Logger
import com.xxjz.ledger.data.local.db.AppDatabase
import com.xxjz.ledger.data.local.entity.AccountEntity
import com.xxjz.ledger.data.local.entity.TxnEntity
import com.xxjz.ledger.domain.model.Ids
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 旧借贷台账 → 借贷账户的一次性搬迁（v1.9.0 / PRD 5.1）。
 *
 * 为什么不在 `MIGRATION_5_6` 里做：迁移是 SQL 层面的结构变更，而搬数据要做的事包括
 * 「按已还金额算出剩余」「为坏账补一条核销流水」「重名账户加序号」——这些写成 SQL
 * 会难以阅读、难以测试，而且无法复用。这里用 DAO 在**一个 Room 事务**里完成，
 * `MIGRATION_5_6` 只负责加列。
 *
 * **幂等性**：不靠标记位，靠「`debt` 表空了就什么都不做」。每搬完一条就随事务一起
 * 删掉旧行，中途崩溃整体回滚，下次重跑还是同一批数据、同一种结果。
 * 备份恢复后也会再跑一次（旧备份里可能带着 `debts`）。
 */
@Singleton
class DebtToAccountMigrator
    @Inject
    constructor(
        private val db: AppDatabase,
    ) {
        private val tag = "DebtMigrator"

        suspend fun run() {
            val debts = runCatching { db.debtDao().listAll() }.getOrElse { return }
            if (debts.isEmpty()) return

            runCatching {
                db.withTransaction {
                    val existing = db.accountDao().listAllIncludingDeleted()
                    val usedNames = existing.mapTo(mutableSetOf()) { it.name }
                    var sortOrder = (existing.maxOfOrNull { it.sortOrder } ?: 0) + 1
                    val now = System.currentTimeMillis()

                    debts.forEach { debt ->
                        val accountId = ACCOUNT_PREFIX + debt.id
                        // 已经有了就跳过：说明上一次迁移已经建过账户，只是 debt 行还没删干净
                        if (existing.any { it.id == accountId }) return@forEach

                        val lending = debt.direction.equals("LEND", ignoreCase = true)
                        val name = uniqueName(debt.counterparty.ifBlank { "借贷" }, usedNames)
                        db.accountDao().insert(
                            AccountEntity(
                                id = accountId,
                                name = name,
                                type = "DEBT",
                                // 待收为正、待还为负（PRD 2.1），这样账户余额的符号本身就表达了方向
                                initialBalance = if (lending) debt.principal else -debt.principal,
                                icon = "",
                                color = "",
                                cardLimit = null,
                                billDay = null,
                                repayDay = null,
                                hidden = false,
                                sortOrder = sortOrder++,
                                debtDirection = if (lending) "LEND" else "BORROW",
                                note = buildNote(debt.note, debt.writtenOff, lending),
                                dueAt = debt.dueAt,
                            ),
                        )

                        // 旧还款流水改挂到借贷账户：借出的补 COLLECT、借入的补 REPAY
                        db.txnDao().relinkDebtTxns(
                            accountId = accountId,
                            debtId = debt.id,
                            action = if (lending) "COLLECT" else "REPAY",
                        )

                        // 坏账核销：只有「我借出」方向成立——钱确实收不回来，净资产要减少。
                        // 「我借入」方向的坏账旧语义是「我欠的钱不还了」，核销会凭空虚增净资产，
                        // 因此只保留备注、余额仍显示待还，由用户自己决定后续怎么处理。
                        if (lending && debt.writtenOff) {
                            val repaid = db.txnDao().repaidTotal(debt.id)
                            val remain = (debt.principal - repaid).coerceAtLeast(0L)
                            if (remain > 0L) {
                                db.txnDao().insert(
                                    TxnEntity(
                                        id = Ids.newId("txn"),
                                        type = "DEBT",
                                        amount = remain,
                                        categoryId = null,
                                        accountId = accountId,
                                        toAccountId = null,
                                        fee = 0L,
                                        dateTime = now,
                                        dateKey = DateUtils.dateKeyOf(now),
                                        note = "坏账核销：$name",
                                        memo = "",
                                        merchantId = null,
                                        merchantName = null,
                                        currency = "CNY",
                                        ledgerId = null,
                                        excludedFromStats = true,
                                        pendingReimbursement = false,
                                        reimbursedAmount = 0L,
                                        reimbursedAt = null,
                                        refundOfTxnId = null,
                                        parentTxnId = null,
                                        debtId = debt.id,
                                        debtAction = "WRITE_OFF",
                                        installmentId = null,
                                        deletedAt = null,
                                        createdAt = now,
                                        updatedAt = now,
                                    ),
                                )
                            }
                        }
                    }

                    // 搬完清空，避免备份把旧台账再带出去、恢复后新旧两份并存
                    db.debtDao().deleteAll()
                }
            }.onFailure { Logger.w(tag, "旧台账迁移失败，已回滚", it) }
        }

        /** 重名加序号：旧台账允许两个同名的人，账户列表里则要能分清。 */
        private fun uniqueName(
            base: String,
            used: MutableSet<String>,
        ): String {
            if (base !in used) {
                used += base
                return base
            }
            var index = 2
            while ("$base($index)" in used) index++
            val name = "$base($index)"
            used += name
            return name
        }

        private fun buildNote(
            note: String,
            writtenOff: Boolean,
            lending: Boolean,
        ): String =
            when {
                !writtenOff -> note
                lending -> note.ifBlank { "原台账标记为坏账，已按坏账核销处理" }
                else -> "原台账标记为坏账（借入方向未自动核销，请自行确认）" + if (note.isBlank()) "" else "；$note"
            }

        companion object {
            /** 借贷账户 id 前缀：由旧台账 id 派生，天然幂等。 */
            const val ACCOUNT_PREFIX = "acc_"
        }
    }
