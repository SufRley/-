package com.xxjz.ledger.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.xxjz.ledger.data.local.dao.AccountDao
import com.xxjz.ledger.data.local.dao.BackupDao
import com.xxjz.ledger.data.local.dao.BudgetDao
import com.xxjz.ledger.data.local.dao.CategoryDao
import com.xxjz.ledger.data.local.dao.DebtDao
import com.xxjz.ledger.data.local.dao.InstallmentDao
import com.xxjz.ledger.data.local.dao.LedgerDao
import com.xxjz.ledger.data.local.dao.MerchantDao
import com.xxjz.ledger.data.local.dao.MetaDao
import com.xxjz.ledger.data.local.dao.MetaEntity
import com.xxjz.ledger.data.local.dao.RecurringDao
import com.xxjz.ledger.data.local.dao.TagDao
import com.xxjz.ledger.data.local.dao.TemplateDao
import com.xxjz.ledger.data.local.dao.TxnDao
import com.xxjz.ledger.data.local.entity.AccountEntity
import com.xxjz.ledger.data.local.entity.BudgetEntity
import com.xxjz.ledger.data.local.entity.CategoryEntity
import com.xxjz.ledger.data.local.entity.DebtEntity
import com.xxjz.ledger.data.local.entity.InstallmentEntity
import com.xxjz.ledger.data.local.entity.LedgerEntity
import com.xxjz.ledger.data.local.entity.MerchantEntity
import com.xxjz.ledger.data.local.entity.RecurringRuleEntity
import com.xxjz.ledger.data.local.entity.TagEntity
import com.xxjz.ledger.data.local.entity.TemplateEntity
import com.xxjz.ledger.data.local.entity.TxnEntity
import com.xxjz.ledger.data.local.entity.TxnTagEntity

/**
 * 应用数据库，version 7。
 *
 * 版本演进（见 [ALL_MIGRATIONS]）：v2 预算表、v3 借贷坏账、v4 账户软删、
 * v5 账户五分类与投资字段、v6 借贷账户化（负债/备注/还款日、借贷动作、分期关联）、
 * v7 分期计划表、v9 分期已执行期数与下次生成时刻。
 *
 * - `exportSchema = true`：schema 快照随仓库走（`app/schemas`），便于后续迁移评审。
 * - WAL：在 `DatabaseModule` 建库时通过 `JournalMode.WRITE_AHEAD_LOGGING` 开启。
 */
@Database(
    entities = [
        TxnEntity::class,
        AccountEntity::class,
        CategoryEntity::class,
        TagEntity::class,
        TxnTagEntity::class,
        MerchantEntity::class,
        TemplateEntity::class,
        BudgetEntity::class,
        RecurringRuleEntity::class,
        DebtEntity::class,
        InstallmentEntity::class,
        LedgerEntity::class,
        MetaEntity::class,
    ],
    version = AppDatabase.SCHEMA_VERSION,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun txnDao(): TxnDao

    abstract fun accountDao(): AccountDao

    abstract fun categoryDao(): CategoryDao

    abstract fun tagDao(): TagDao

    abstract fun merchantDao(): MerchantDao

    abstract fun templateDao(): TemplateDao

    abstract fun budgetDao(): BudgetDao

    abstract fun recurringDao(): RecurringDao

    abstract fun debtDao(): DebtDao

    abstract fun installmentDao(): InstallmentDao

    abstract fun ledgerDao(): LedgerDao

    abstract fun metaDao(): MetaDao

    abstract fun backupDao(): BackupDao

    companion object {
        /** 当前 schema 版本；备份与导入的兼容性判断都读这里。 */
        const val SCHEMA_VERSION = 9

        const val NAME = "xxjz.db"
        const val SEED_VERSION = 1
        const val KEY_SEED = "seed_version"
    }
}
