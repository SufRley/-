package com.xxjz.ledger.data.repository

import com.xxjz.ledger.data.local.dao.CategoryDao
import com.xxjz.ledger.data.local.dao.TxnDao
import com.xxjz.ledger.domain.model.Category
import com.xxjz.ledger.domain.model.TxnType
import com.xxjz.ledger.domain.repository.CategoryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CategoryRepositoryImpl
    @Inject
    constructor(
        private val dao: CategoryDao,
        private val txnDao: TxnDao,
    ) : CategoryRepository {
        override fun observeAll(): Flow<List<Category>> = dao.observeAll().map { list -> list.map { it.toModel() } }

        override suspend fun listAll(): List<Category> = dao.listAll().map { it.toModel() }

        override suspend fun byId(id: String): Category? = dao.byId(id)?.toModel()

        override suspend fun tree(type: TxnType): List<Pair<Category, List<Category>>> {
            val all = dao.listByType(type.name).map { it.toModel() }
            val byParent = all.filter { it.parentId != null }.groupBy { it.parentId }
            return all.filter { it.parentId == null }.map { major -> major to (byParent[major.id] ?: emptyList()) }
        }

        override suspend fun frequent(
            type: TxnType,
            limit: Int,
        ): List<Category> =
            dao.listByType(type.name)
                .map { it.toModel() }
                .filter { it.parentId != null || dao.childrenOf(it.id).isEmpty() }
                .sortedWith(compareByDescending<Category> { it.usageCount }.thenByDescending { it.pinned })
                .take(limit)

        override suspend fun increaseUsage(id: String) = dao.increaseUsage(id)

        override suspend fun upsert(category: Category) = dao.upsert(category.toEntity())

        /**
         * 分类下（含子类）的流水条数。
         *
         * 只统计直系子类一级：本 App 的分类树最多两层（大类 → 小类），
         * 不需要递归；真要支持三层时这里得改成 BFS。
         */
        override suspend fun txnCountOf(id: String): Int {
            val children = dao.childrenOf(id).map { it.id }
            return (listOf(id) + children).sumOf { txnDao.usageCountOf(it) }
        }

        override suspend fun setHidden(
            id: String,
            hidden: Boolean,
        ) = dao.setHidden(id, hidden)

        override suspend fun delete(id: String) = dao.delete(id)
    }
