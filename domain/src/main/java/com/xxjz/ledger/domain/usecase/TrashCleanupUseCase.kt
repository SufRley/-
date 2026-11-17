package com.xxjz.ledger.domain.usecase

import com.xxjz.ledger.core.common.AppResult
import com.xxjz.ledger.domain.repository.BackupRepository
import com.xxjz.ledger.domain.repository.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 回收站定时清理（AC-76）。
 *
 * 规则：`deletedAt + 保留天数 < now` 的条目物理删除。
 * 保留天数设为 0（永久）时整体跳过，一条都不动。
 *
 * 清理哪些表、事务怎么包在 [BackupRepository] 的实现里；这里只保留策略。
 */
@Singleton
class TrashCleanupUseCase
    @Inject
    constructor(
        private val backupRepository: BackupRepository,
        private val settings: SettingsRepository,
    ) {
        suspend operator fun invoke(now: Long = System.currentTimeMillis()): AppResult<Int> {
            val days = settings.current().recycleBinDays
            if (days <= 0) return AppResult.Success(0)
            val cutoff = now - days.toLong() * DAY_MILLIS
            return AppResult.Success(backupRepository.purgeTrashBefore(cutoff))
        }

        companion object {
            const val DAY_MILLIS = 24L * 60 * 60 * 1000
        }
    }
