package com.xxjz.ledger.data.repository

import com.xxjz.ledger.data.local.dao.TemplateDao
import com.xxjz.ledger.domain.model.Template
import com.xxjz.ledger.domain.repository.TemplateRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TemplateRepositoryImpl
    @Inject
    constructor(
        private val dao: TemplateDao,
    ) : TemplateRepository {
        override fun observeAll(): Flow<List<Template>> = dao.observeAll().map { list -> list.map { it.toModel() } }

        override suspend fun byId(id: String): Template? = dao.byId(id)?.toModel()

        override suspend fun upsert(template: Template) = dao.upsert(template.toEntity())

        override suspend fun delete(id: String) = dao.deleteById(id)
    }
