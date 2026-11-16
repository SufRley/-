package com.xxjz.ledger.data.repository

import com.xxjz.ledger.data.local.dao.MerchantDao
import com.xxjz.ledger.data.local.entity.MerchantEntity
import com.xxjz.ledger.domain.model.Ids
import com.xxjz.ledger.domain.model.Merchant
import com.xxjz.ledger.domain.repository.MerchantRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MerchantRepositoryImpl
    @Inject
    constructor(
        private val dao: MerchantDao,
    ) : MerchantRepository {
        override suspend fun suggest(limit: Int): List<Merchant> =
            dao.suggest(limit).map { Merchant(id = it.id, name = it.name, usageCount = it.usageCount) }

        override suspend fun findOrCreate(name: String): Merchant {
            val trimmed = name.trim()
            dao.byName(trimmed)?.let {
                dao.increaseUsage(it.id)
                return Merchant(id = it.id, name = it.name, usageCount = it.usageCount + 1)
            }
            val merchant = MerchantEntity(id = Ids.newId("merchant"), name = trimmed, usageCount = 1)
            dao.insert(merchant)
            return Merchant(id = merchant.id, name = merchant.name, usageCount = 1)
        }

        override suspend fun listAll(): List<Merchant> =
            dao.listAll().map { Merchant(id = it.id, name = it.name, usageCount = it.usageCount) }

        /** 改名走 upsert：先按 id 取原行，改名字后整体写回，保留 usageCount。 */
        override suspend fun rename(
            id: String,
            name: String,
        ) {
            val existing = dao.listAll().firstOrNull { it.id == id } ?: return
            dao.insert(existing.copy(name = name.trim()))
        }

        override suspend fun delete(id: String) = dao.delete(id)
    }
