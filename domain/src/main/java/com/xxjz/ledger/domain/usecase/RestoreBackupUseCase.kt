package com.xxjz.ledger.domain.usecase

import com.xxjz.ledger.core.common.AppError
import com.xxjz.ledger.core.common.AppResult
import com.xxjz.ledger.core.common.Logger
import com.xxjz.ledger.domain.model.BackupPayload
import com.xxjz.ledger.domain.model.BackupStats
import com.xxjz.ledger.domain.repository.BackupRepository
import com.xxjz.ledger.domain.repository.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 覆盖导入（AC-73、AC-74）。
 *
 * 语义是**整库替换**，不是合并：先清空全部业务表，再写入备份内容。
 * 落库细节（清表、写表、事务、旧 debt 搬迁）在 [BackupRepository] 的实现里；
 * 这里只负责策略：空备份拦截、失败翻译成人话、事务成功后才写偏好设置——
 * 因为 DataStore 不参与 Room 事务，顺序反了会出现「设置变了但数据没进来」。
 */
@Singleton
class RestoreBackupUseCase
    @Inject
    constructor(
        private val backupRepository: BackupRepository,
        private val settings: SettingsRepository,
    ) {
        private val tag = "RestoreBackup"

        suspend operator fun invoke(payload: BackupPayload): AppResult<BackupStats> {
            if (payload.accounts.isEmpty() && payload.txns.isEmpty()) {
                return AppResult.Failure(AppError.Custom("备份里没有任何数据，已取消导入"))
            }
            try {
                backupRepository.replaceAll(payload)
            } catch (e: Exception) {
                Logger.w(tag, "导入事务失败，已回滚", e)
                return AppResult.Failure(AppError.Custom("导入失败，数据已回滚到导入前"))
            }
            // 数据已经落库，设置写失败不该让用户误以为整次导入失败
            runCatching { settings.restoreFrom(payload.settings) }
            return AppResult.Success(payload.stats)
        }
    }
