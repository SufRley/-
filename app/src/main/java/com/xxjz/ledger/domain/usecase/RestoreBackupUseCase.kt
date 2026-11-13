package com.xxjz.ledger.domain.usecase

import androidx.room.withTransaction
import com.xxjz.ledger.core.common.AppError
import com.xxjz.ledger.core.common.AppResult
import com.xxjz.ledger.data.backup.BackupPayload
import com.xxjz.ledger.data.backup.BackupStats
import com.xxjz.ledger.data.backup.toEntity
import com.xxjz.ledger.data.datastore.SettingsDataStore
import com.xxjz.ledger.data.local.db.AppDatabase
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 覆盖导入（AC-73、AC-74）。
 *
 * 语义是**整库替换**，不是合并：先清空全部业务表，再写入备份内容。
 * 全部动作包在 Room 的一个事务里 —— 中途任何一步失败都会整体回滚，
 * 不会出现「删了一半」的灾难现场。事务提交成功之后才写偏好设置，
 * 因为 DataStore 不参与 Room 事务，顺序反了会出现「设置变了但数据没进来」。
 */
@Singleton
class RestoreBackupUseCase
    @Inject
    constructor(
        private val db: AppDatabase,
        private val settings: SettingsDataStore,
    ) {
        /**
         * 故障注入点：清空表之后、写数据之前调用。
         *
         * 仅供测试验证「中途失败整体回滚」这一 AC，生产路径永远是 null。
         */
        internal var faultInjector: (() -> Unit)? = null

        suspend operator fun invoke(payload: BackupPayload): AppResult<BackupStats> {
            if (payload.accounts.isEmpty() && payload.txns.isEmpty()) {
                return AppResult.Failure(AppError.Custom("备份里没有任何数据，已取消导入"))
            }
            try {
                db.withTransaction { replaceAll(payload) }
            } catch (e: Exception) {
                return AppResult.Failure(AppError.Custom("导入失败，数据已回滚到导入前"))
            }
            // 数据已经落库，设置写失败不该让用户误以为整次导入失败
            runCatching { settings.restoreFrom(payload.settings) }
            return AppResult.Success(payload.stats)
        }

        private suspend fun replaceAll(payload: BackupPayload) {
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
    }
