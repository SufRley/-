package com.xxjz.ledger.data.di

import android.content.Context
import androidx.room.Room
import com.xxjz.ledger.data.local.dao.AccountDao
import com.xxjz.ledger.data.local.dao.BudgetDao
import com.xxjz.ledger.data.local.dao.CategoryDao
import com.xxjz.ledger.data.local.dao.DebtDao
import com.xxjz.ledger.data.local.dao.InstallmentDao
import com.xxjz.ledger.data.local.dao.LedgerDao
import com.xxjz.ledger.data.local.dao.MerchantDao
import com.xxjz.ledger.data.local.dao.MetaDao
import com.xxjz.ledger.data.local.dao.RecurringDao
import com.xxjz.ledger.data.local.dao.TagDao
import com.xxjz.ledger.data.local.dao.TemplateDao
import com.xxjz.ledger.data.local.dao.TxnDao
import com.xxjz.ledger.data.local.db.ALL_MIGRATIONS
import com.xxjz.ledger.data.local.db.AppDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** 数据库与 DAO 的 Hilt 装配。 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
    ): AppDatabase =
        Room
            .databaseBuilder(context, AppDatabase::class.java, AppDatabase.NAME)
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            // 逐个注册迁移：迁移表是编译期常量数组，spread 会多拷贝一次（detekt SpreadOperator 也会报）
            .apply { ALL_MIGRATIONS.forEach { addMigrations(it) } }
            .fallbackToDestructiveMigrationOnDowngrade()
            .build()

    @Provides
    fun provideTxnDao(db: AppDatabase): TxnDao = db.txnDao()

    @Provides
    fun provideAccountDao(db: AppDatabase): AccountDao = db.accountDao()

    @Provides
    fun provideCategoryDao(db: AppDatabase): CategoryDao = db.categoryDao()

    @Provides
    fun provideTagDao(db: AppDatabase): TagDao = db.tagDao()

    @Provides
    fun provideMerchantDao(db: AppDatabase): MerchantDao = db.merchantDao()

    @Provides
    fun provideTemplateDao(db: AppDatabase): TemplateDao = db.templateDao()

    @Provides
    fun provideBudgetDao(db: AppDatabase): BudgetDao = db.budgetDao()

    @Provides
    fun provideRecurringDao(db: AppDatabase): RecurringDao = db.recurringDao()

    @Provides
    fun provideDebtDao(db: AppDatabase): DebtDao = db.debtDao()

    @Provides
    @Singleton
    fun provideInstallmentDao(db: AppDatabase): InstallmentDao = db.installmentDao()

    @Provides
    fun provideLedgerDao(db: AppDatabase): LedgerDao = db.ledgerDao()

    @Provides
    fun provideMetaDao(db: AppDatabase): MetaDao = db.metaDao()
}
