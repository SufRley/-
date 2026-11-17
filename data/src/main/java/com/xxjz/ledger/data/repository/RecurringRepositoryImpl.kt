package com.xxjz.ledger.data.repository

import com.xxjz.ledger.data.local.dao.RecurringDao
import com.xxjz.ledger.domain.model.RecurringRule
import com.xxjz.ledger.domain.repository.RecurringRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RecurringRepositoryImpl
    @Inject
    constructor(
        private val dao: RecurringDao,
    ) : RecurringRepository {
        override fun observeAll(): Flow<List<RecurringRule>> = dao.observeAll().map { list -> list.map { it.toModel() } }

        override suspend fun due(now: Long): List<RecurringRule> = dao.dueRules(now).map { it.toModel() }

        override suspend fun byId(id: String): RecurringRule? = dao.byId(id)?.toModel()

        override suspend fun upsert(rule: RecurringRule) = dao.upsert(rule.toEntity())

        override suspend fun setPaused(
            id: String,
            paused: Boolean,
            nextRunAt: Long,
        ) = dao.setPaused(id, paused, nextRunAt)

        override suspend fun advance(
            id: String,
            nextRunAt: Long,
            runTimes: Int,
            paused: Boolean,
        ) = dao.advance(id, nextRunAt, runTimes, paused)

        override suspend fun delete(id: String) = dao.delete(id)
    }
