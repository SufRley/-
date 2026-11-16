package com.xxjz.ledger.data.backup

import com.xxjz.ledger.BuildConfig
import com.xxjz.ledger.data.local.db.AppDatabase
import com.xxjz.ledger.domain.model.AppSettings
import com.xxjz.ledger.domain.model.BackupPayload
import com.xxjz.ledger.domain.model.BackupSettings
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 把当前库里的全部数据拍成一份 [BackupPayload]。
 *
 * 读取**包含回收站**的内容：导出的文件必须能一比一还原，否则「备份」就有损了。
 * 余额不参与备份（由流水实时算出），避免导回去之后对不上。
 */
@Singleton
class BackupPayloadBuilder
    @Inject
    constructor(
        private val db: AppDatabase,
    ) {
        suspend fun build(
            settings: AppSettings,
            now: Long = System.currentTimeMillis(),
            appVersion: String = BuildConfig.VERSION_NAME,
        ): BackupPayload {
            val txnDao = db.txnDao()
            val accountDao = db.accountDao()
            val categoryDao = db.categoryDao()
            val tagDao = db.tagDao()
            val merchantDao = db.merchantDao()
            val templateDao = db.templateDao()
            val budgetDao = db.budgetDao()
            val recurringDao = db.recurringDao()
            val debtDao = db.debtDao()
            val ledgerDao = db.ledgerDao()

            return BackupPayload(
                payloadVersion = BackupPayload.CURRENT_PAYLOAD_VERSION,
                schemaVersion = AppDatabase.SCHEMA_VERSION,
                appVersion = appVersion,
                createdAt = now,
                settings =
                    BackupSettings(
                        defaultAccountId = settings.defaultAccountId,
                        defaultTimeMode = settings.defaultTimeMode.name,
                        fixedTimeMinutes = settings.fixedTimeMinutes,
                        decimalPlaces = settings.decimalPlaces,
                        keypadType = settings.keypadType.name,
                        amountColorsReversed = settings.amountColorsReversed,
                        themeMode = settings.themeMode,
                        recycleBinDays = settings.recycleBinDays,
                        backupReminderEnabled = settings.backupReminderEnabled,
                        lastTxnType = settings.lastTxnType.name,
                        savingGoalCents = settings.savingGoalCents,
                    ),
                txns = txnDao.listAllIncludingDeleted().map { it.toDto() },
                accounts = accountDao.listAllIncludingDeleted().map { it.toDto() },
                categories = categoryDao.listAll().map { it.toDto() },
                tags = tagDao.listAll().map { it.toDto() },
                txnTags = tagDao.listAllLinks().map { it.toDto() },
                merchants = merchantDao.listAll().map { it.toDto() },
                templates = templateDao.listAll().map { it.toDto() },
                budgets = budgetDao.listAllIncludingDeleted().map { it.toDto() },
                recurringRules = recurringDao.listAll().map { it.toDto() },
                debts = debtDao.listAll().map { it.toDto() },
                ledgers = ledgerDao.listAll().map { it.toDto() },
            )
        }
    }
