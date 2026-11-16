package com.xxjz.ledger.data.repository

import com.xxjz.ledger.data.local.dao.BudgetDao
import com.xxjz.ledger.domain.model.Budget
import com.xxjz.ledger.domain.repository.BudgetRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BudgetRepositoryImpl
    @Inject
    constructor(
        private val dao: BudgetDao,
    ) : BudgetRepository {
        override fun observeMonthRows(monthKey: Int): Flow<List<Budget>> =
            dao.observeMonthRows(monthKey).map { list -> list.map { it.toModel() } }

        override fun observeLatestBefore(monthKey: Int): Flow<List<Budget>> =
            dao.observeLatestBefore(monthKey).map { list -> list.map { it.toModel() } }

        override suspend fun monthRows(monthKey: Int): List<Budget> = dao.monthRows(monthKey).map { it.toModel() }

        override suspend fun latestBefore(monthKey: Int): List<Budget> = dao.latestBefore(monthKey).map { it.toModel() }

        override suspend fun byId(
            id: String,
            monthKey: Int,
        ): Budget? = dao.byId(id, monthKey)?.toModel()

        override suspend fun upsert(budget: Budget) = dao.upsert(budget.toEntity())

        override suspend fun upsertAll(list: List<Budget>) = dao.insertAll(list.map { it.toEntity() })

        override suspend fun softDelete(
            id: String,
            monthKey: Int,
            at: Long,
        ) = dao.softDelete(id, monthKey, at)

        override suspend fun restore(
            id: String,
            monthKey: Int,
        ) = dao.restore(id, monthKey)

        override fun observeDeleted(): Flow<List<Budget>> = dao.observeDeleted().map { list -> list.map { it.toModel() } }

        override suspend fun setEnabled(
            id: String,
            monthKey: Int,
            enabled: Boolean,
        ) = dao.setEnabled(id, monthKey, enabled)

        override suspend fun delete(
            id: String,
            monthKey: Int,
        ) = dao.delete(id, monthKey)
    }
