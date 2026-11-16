package com.xxjz.ledger.data.repository

import androidx.room.withTransaction
import com.xxjz.ledger.data.backup.toEntity
import com.xxjz.ledger.data.local.db.AppDatabase
import com.xxjz.ledger.domain.model.BackupPayload
import com.xxjz.ledger.domain.repository.BackupRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [BackupRepository] 的 Room 实现：整库替换与回收站物理清理的落库细节都在这里。
 *
 * 从 domain 用例下沉而来（原先 `RestoreBackupUseCase` / `TrashCleanupUseCase`
 * 直接持有 [AppDatabase]）：用例层只负责策略（校验、统计、文案），
 * 「清哪些表、按什么顺序写、事务怎么包」属于存储实现。
 */
@Singleton
class BackupRepositoryImpl
    @Inject
    constructor(
        private val db: AppDatabase,
        private val debtMigrator: DebtToAccountMigrator,
    ) : BackupRepository {
        override val schemaVersion: Int get() = AppDatabase.SCHEMA_VERSION

        /**
         * 故障注入点：清空表之后、写数据之前调用。
         *
         * 仅供测试验证「中途失败整体回滚」这一 AC，生产路径永远是 null。
         */
        internal var faultInjector: (() -> Unit)? = null

        override suspend fun replaceAll(payload: BackupPayload) {
            db.withTransaction {
                val dao = db.backupDao()
                dao.clearTxnTags()
                dao.clearTxns()
                dao.clearAccounts()
                dao.clearCategories()
                dao.clearTags()
                dao.clearMerchants()
                dao.clearTemplates()
                dao.clearBudgets()
                dao.clearRecurringRules()
                dao.clearDebts()
                dao.clearLedgers()

                faultInjector?.invoke()

                dao.insertAccounts(payload.accounts.map { it.toEntity() })
                dao.insertCategories(payload.categories.map { it.toEntity() })
                dao.insertTags(payload.tags.map { it.toEntity() })
                dao.insertTxns(payload.txns.map { it.toEntity() })
                dao.insertTxnTags(payload.txnTags.map { it.toEntity() })
                dao.insertMerchants(payload.merchants.map { it.toEntity() })
                dao.insertTemplates(payload.templates.map { it.toEntity() })
                dao.insertBudgets(payload.budgets.map { it.toEntity() })
                dao.insertRecurringRules(payload.recurringRules.map { it.toEntity() })
                dao.insertDebts(payload.debts.map { it.toEntity() })
                dao.insertLedgers(payload.ledgers.map { it.toEntity() })
            }
            // 旧版本备份里带着借贷台账，恢复到 v1.9.0 后要再搬一次成账户；
            // debt 表为空时内部直接返回，是空操作。
            runCatching { debtMigrator.run() }
        }

        override suspend fun purgeTrashBefore(cutoff: Long): Int {
            var purged = 0
            db.withTransaction {
                val dao = db.backupDao()
                purged += dao.purgeTxnsBefore(cutoff)
                purged += dao.purgeAccountsBefore(cutoff)
                purged += dao.purgeBudgetsBefore(cutoff)
                dao.purgeOrphanTxnTags()
            }
            return purged
        }
    }
