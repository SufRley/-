package com.xxjz.ledger.data.di

import android.content.Context
import com.xxjz.ledger.data.datastore.SettingsDataStore
import com.xxjz.ledger.data.io.ContentResolverDocumentIo
import com.xxjz.ledger.data.io.DocumentIo
import com.xxjz.ledger.data.repository.AccountRepositoryImpl
import com.xxjz.ledger.data.repository.AssetEventStore
import com.xxjz.ledger.data.repository.BackupRepositoryImpl
import com.xxjz.ledger.data.repository.BudgetRepositoryImpl
import com.xxjz.ledger.data.repository.CategoryRepositoryImpl
import com.xxjz.ledger.data.repository.InstallmentRepositoryImpl
import com.xxjz.ledger.data.repository.MerchantRepositoryImpl
import com.xxjz.ledger.data.repository.RecurringRepositoryImpl
import com.xxjz.ledger.data.repository.TagRepositoryImpl
import com.xxjz.ledger.data.repository.TemplateRepositoryImpl
import com.xxjz.ledger.data.repository.TxnRepositoryImpl
import com.xxjz.ledger.domain.repository.AccountRepository
import com.xxjz.ledger.domain.repository.AssetEventRepository
import com.xxjz.ledger.domain.repository.BackupRepository
import com.xxjz.ledger.domain.repository.BudgetRepository
import com.xxjz.ledger.domain.repository.CategoryRepository
import com.xxjz.ledger.domain.repository.InstallmentRepository
import com.xxjz.ledger.domain.repository.MerchantRepository
import com.xxjz.ledger.domain.repository.RecurringRepository
import com.xxjz.ledger.domain.repository.SettingsRepository
import com.xxjz.ledger.domain.repository.TagRepository
import com.xxjz.ledger.domain.repository.TemplateRepository
import com.xxjz.ledger.domain.repository.TxnRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** 仓库接口 → 实现的绑定。 */
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds
    @Singleton
    abstract fun bindTxnRepository(impl: TxnRepositoryImpl): TxnRepository

    @Binds
    @Singleton
    abstract fun bindAccountRepository(impl: AccountRepositoryImpl): AccountRepository

    @Binds
    @Singleton
    abstract fun bindCategoryRepository(impl: CategoryRepositoryImpl): CategoryRepository

    @Binds
    @Singleton
    abstract fun bindTagRepository(impl: TagRepositoryImpl): TagRepository

    @Binds
    @Singleton
    abstract fun bindMerchantRepository(impl: MerchantRepositoryImpl): MerchantRepository

    @Binds
    @Singleton
    abstract fun bindTemplateRepository(impl: TemplateRepositoryImpl): TemplateRepository

    @Binds
    @Singleton
    abstract fun bindBudgetRepository(impl: BudgetRepositoryImpl): BudgetRepository

    @Binds
    @Singleton
    abstract fun bindRecurringRepository(impl: RecurringRepositoryImpl): RecurringRepository

    /** 偏好设置也按仓库方式绑定：业务层只认接口，测试里能换内存实现。 */
    @Binds
    @Singleton
    abstract fun bindSettingsRepository(impl: SettingsDataStore): SettingsRepository

    /** 备份的整库替换与回收站清理：Room 细节关在实现里。 */
    @Binds
    @Singleton
    abstract fun bindBackupRepository(impl: BackupRepositoryImpl): BackupRepository

    /** 资产变化事件存储：domain 只认契约。 */
    @Binds
    @Singleton
    abstract fun bindAssetEventRepository(impl: AssetEventStore): AssetEventRepository

    @Binds
    @Singleton
    abstract fun bindInstallmentRepository(impl: InstallmentRepositoryImpl): InstallmentRepository
}

/** 文件读写通道（SAF）。 */
@Module
@InstallIn(SingletonComponent::class)
object DocumentIoModule {
    @Provides
    @Singleton
    fun provideDocumentIo(
        @ApplicationContext context: Context,
    ): DocumentIo = ContentResolverDocumentIo(context.contentResolver)
}
