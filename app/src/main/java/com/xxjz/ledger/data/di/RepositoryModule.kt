package com.xxjz.ledger.data.di

import android.content.Context
import com.xxjz.ledger.data.io.ContentResolverDocumentIo
import com.xxjz.ledger.data.io.DocumentIo
import com.xxjz.ledger.data.repository.AccountRepositoryImpl
import com.xxjz.ledger.data.repository.BudgetRepositoryImpl
import com.xxjz.ledger.data.repository.CategoryRepositoryImpl
import com.xxjz.ledger.data.repository.DebtRepositoryImpl
import com.xxjz.ledger.data.repository.MerchantRepositoryImpl
import com.xxjz.ledger.data.repository.RecurringRepositoryImpl
import com.xxjz.ledger.data.repository.TagRepositoryImpl
import com.xxjz.ledger.data.repository.TemplateRepositoryImpl
import com.xxjz.ledger.data.repository.TxnRepositoryImpl
import com.xxjz.ledger.domain.repository.AccountRepository
import com.xxjz.ledger.domain.repository.BudgetRepository
import com.xxjz.ledger.domain.repository.CategoryRepository
import com.xxjz.ledger.domain.repository.DebtRepository
import com.xxjz.ledger.domain.repository.MerchantRepository
import com.xxjz.ledger.domain.repository.RecurringRepository
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
    abstract fun bindDebtRepository(impl: DebtRepositoryImpl): DebtRepository

    @Binds
    @Singleton
    abstract fun bindRecurringRepository(impl: RecurringRepositoryImpl): RecurringRepository
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
