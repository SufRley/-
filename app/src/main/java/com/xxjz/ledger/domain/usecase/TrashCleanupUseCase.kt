package com.xxjz.ledger.domain.usecase

import androidx.room.withTransaction
import com.xxjz.ledger.core.common.AppResult
import com.xxjz.ledger.data.datastore.SettingsDataStore
import com.xxjz.ledger.data.local.db.AppDatabase
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 回收站定时清理（AC-76）。
 *
 * 规则：`deletedAt + 保留天数 < now` 的条目物理删除。
 * 保留天数设为 0（永久）时整体跳过，一条都不动。
 *
 * 账目、账户、预算三张表各扫一遍，最后清掉没有归属的 `txn_tag` 关联，
 * 避免回收站清空之后留下一堆孤儿行。
 */
@Singleton
class TrashCleanupUseCase
    @Inject
    constructor(
        private val db: AppDatabase,
        private val settings: SettingsDataStore,
    ) {
        suspend operator fun invoke(now: Long = System.currentTimeMillis()): AppResult<Int> {
            val days = settings.current().recycleBinDays
            if (days <= 0) return AppResult.Success(0)
            val cutoff = now - days.toLong() * DAY_MILLIS
            var purged = 0
            db.withTransaction {
                val dao = db.backupDao()
                purged += dao.purgeTxnsBefore(cutoff)
                purged += dao.purgeAccountsBefore(cutoff)
                purged += dao.purgeBudgetsBefore(cutoff)
                dao.purgeOrphanTxnTags()
            }
            return AppResult.Success(purged)
        }

        companion object {
            const val DAY_MILLIS = 24L * 60 * 60 * 1000
        }
    }
