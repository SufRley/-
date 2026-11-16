package com.xxjz.ledger.data.repository

import androidx.room.withTransaction
import com.xxjz.ledger.data.local.dao.AccountDao
import com.xxjz.ledger.data.local.db.AppDatabase
import com.xxjz.ledger.domain.model.Account
import com.xxjz.ledger.domain.model.AccountType
import com.xxjz.ledger.domain.model.AssetEvent
import com.xxjz.ledger.domain.model.AssetEventKind
import com.xxjz.ledger.domain.repository.AccountRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AccountRepositoryImpl
    @Inject
    constructor(
        private val db: AppDatabase,
        private val dao: AccountDao,
        private val assetEvents: AssetEventStore,
    ) : AccountRepository {
        private val txnDao get() = db.txnDao()

        override fun observeVisible(): Flow<List<Account>> = dao.observeVisible().map { list -> list.map { it.toModel() } }

        override fun observeAll(): Flow<List<Account>> = dao.observeAll().map { list -> list.map { it.toModel() } }

        override fun observeWithBalances(): Flow<List<Account>> =
            combine(dao.observeAll(), dao.observeBalances()) { accounts, balances ->
                val map = balances.associate { it.accountId to it.balance }
                accounts.map { it.toModel(map[it.id] ?: it.initialBalance) }
            }

        override suspend fun byId(id: String): Account? = dao.byId(id)?.toModel()

        override suspend fun balanceOf(id: String): Long = dao.balanceOf(id)?.balance ?: 0L

        override suspend fun upsert(account: Account) = dao.upsert(account.toEntity())

        override suspend fun count(): Int = dao.count()

        override fun observeDeleted(): Flow<List<Account>> = dao.observeDeleted().map { list -> list.map { it.toModel() } }

        override suspend fun deleteWithTxns(
            id: String,
            at: Long,
        ) {
            db.withTransaction {
                txnDao.softDeleteByAccount(id, at)
                dao.softDelete(id, at)
            }
        }

        override suspend fun restore(
            id: String,
            at: Long,
        ) {
            db.withTransaction {
                val deletedAt = dao.deletedAtOf(id)
                dao.restore(id)
                // 只捞回「同一次删除」连带下水的流水，用户自己单独删的不动
                if (deletedAt != null) txnDao.restoreByAccount(id, deletedAt, at)
            }
        }

        override suspend fun purge(id: String) {
            db.withTransaction {
                txnDao.hardDeleteByAccount(id)
                dao.delete(id)
            }
        }

        override suspend fun setHidden(
            id: String,
            hidden: Boolean,
        ) = dao.setHidden(id, hidden)

        override suspend fun setSortOrder(
            id: String,
            order: Int,
        ) = dao.setSortOrder(id, order)

        override suspend fun txnCount(id: String): Int = txnDao.countByAccount(id)

        override suspend fun adjustInvestCost(
            id: String,
            deltaCents: Long,
            at: Long,
        ) {
            if (deltaCents == 0L) return
            val entity = dao.byId(id) ?: return
            // 非投资账户没有成本概念，直接跳过，省得调用点到处 if (type == INVEST)
            if (entity.type != AccountType.INVEST.name) return
            val next = (entity.costCents + deltaCents).coerceAtLeast(0L)
            dao.update(entity.copy(costCents = next))
        }

        override suspend fun updateMarketValue(
            id: String,
            marketValueCents: Long,
            at: Long,
        ) {
            val entity = dao.byId(id) ?: return
            if (entity.type != AccountType.INVEST.name) return
            val next = marketValueCents.coerceAtLeast(0L)
            val delta = next - entity.marketValueCents
            db.withTransaction {
                dao.update(entity.copy(marketValueCents = next))
                // 改市值不改流水，只在计入净资产时才算净资产变化，写一条事件供报表展示
                if (delta != 0L && entity.includeInNetAssets) {
                    assetEvents.record(
                        AssetEvent(
                            id = "mv_${id}_$at",
                            at = at,
                            kind = AssetEventKind.MARKET_VALUE,
                            accountId = id,
                            accountName = entity.name,
                            // deltaCents 存差额（净资产回放按差额累加），
                            // amountAfterCents 存调整到的新市值（PRD D14 的展示口径）
                            deltaCents = delta,
                            amountAfterCents = next,
                        ),
                    )
                }
            }
        }

        /**
         * 借贷账户合并（v1.9.0）：把源账户的流水整体改挂到目标账户。
         *
         * 借贷流水的 `accountId` 指借贷账户、`toAccountId` 指资金账户，
         * 所以只改 `accountId`，不能顺手把 `toAccountId` 也改——那会把资金账户改成借贷账户。
         */
        override suspend fun moveTxnsTo(
            fromId: String,
            toId: String,
            at: Long,
        ) {
            if (fromId == toId) return
            val rows = txnDao.listByAccount(fromId)
            if (rows.isEmpty()) return
            db.withTransaction {
                rows.forEach { row ->
                    val next =
                        when {
                            // from 出现在 accountId：借贷账户 / 付款账户
                            row.accountId == fromId && row.toAccountId == fromId ->
                                row.copy(accountId = toId, toAccountId = toId, updatedAt = at)
                            row.accountId == fromId -> row.copy(accountId = toId, updatedAt = at)
                            // from 只出现在 toAccountId：收入 / 转入 / 借贷资金侧
                            row.toAccountId == fromId -> row.copy(toAccountId = toId, updatedAt = at)
                            else -> row
                        }
                    if (next != row) txnDao.update(next)
                }
            }
        }
    }
