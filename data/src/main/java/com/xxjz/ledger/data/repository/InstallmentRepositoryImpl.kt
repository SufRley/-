package com.xxjz.ledger.data.repository

import com.xxjz.ledger.data.local.dao.InstallmentDao
import com.xxjz.ledger.domain.model.Installment
import com.xxjz.ledger.domain.repository.InstallmentRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** 分期计划仓库实现（v1.9.0 / PRD 3.2）。 */
@Singleton
class InstallmentRepositoryImpl
    @Inject
    constructor(
        private val dao: InstallmentDao,
    ) : InstallmentRepository {
        override fun observeByAccount(accountId: String): Flow<List<Installment>> =
            dao.observeByAccount(accountId).map { list -> list.map { it.toModel() } }

        override fun observeAll(): Flow<List<Installment>> = dao.observeAll().map { list -> list.map { it.toModel() } }

        override suspend fun listByAccount(accountId: String): List<Installment> = dao.listByAccount(accountId).map { it.toModel() }

        override suspend fun byId(id: String): Installment? = dao.byId(id)?.toModel()

        override suspend fun upsert(plan: Installment) = dao.upsert(plan.toEntity())

        override suspend fun delete(id: String) = dao.delete(id)
    }
