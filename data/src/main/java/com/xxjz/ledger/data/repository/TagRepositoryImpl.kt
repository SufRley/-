package com.xxjz.ledger.data.repository

import com.xxjz.ledger.data.local.dao.TagDao
import com.xxjz.ledger.data.local.entity.TagEntity
import com.xxjz.ledger.data.local.entity.TxnTagEntity
import com.xxjz.ledger.domain.model.Ids
import com.xxjz.ledger.domain.model.Tag
import com.xxjz.ledger.domain.repository.TagRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TagRepositoryImpl
    @Inject
    constructor(
        private val dao: TagDao,
    ) : TagRepository {
        override fun observeAll(): Flow<List<Tag>> = dao.observeAll().map { list -> list.map { it.toModel() } }

        override suspend fun listAll(): List<Tag> = dao.listAll().map { it.toModel() }

        override fun observeTagIdsOf(txnId: String): Flow<List<String>> = dao.observeTagIdsOf(txnId)

        override suspend fun upsert(tag: Tag): Tag {
            dao.upsert(TagEntity(id = tag.id, name = tag.name, color = tag.color, usageCount = tag.usageCount))
            return tag
        }

        override suspend fun findOrCreate(name: String): Tag {
            val existing = dao.listAll().firstOrNull { it.name == name.trim() }
            if (existing != null) return existing.toModel()
            val tag = Tag(id = Ids.newId("tag"), name = name.trim())
            dao.upsert(TagEntity(id = tag.id, name = tag.name, color = tag.color, usageCount = 0))
            return tag
        }

        override suspend fun setTxnTags(
            txnId: String,
            tagIds: List<String>,
        ) {
            dao.clearTags(txnId)
            if (tagIds.isNotEmpty()) {
                dao.linkTags(tagIds.map { TxnTagEntity(txnId = txnId, tagId = it) })
                tagIds.forEach { dao.increaseUsage(it) }
            }
        }

        override suspend fun tagIdsOf(txnId: String): List<String> = dao.tagIdsOf(txnId)

        override suspend fun delete(id: String) = dao.delete(id)
    }
