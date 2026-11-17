package com.xxjz.ledger.data.repository

import com.xxjz.ledger.data.local.dao.CategoryDao
import com.xxjz.ledger.data.local.dao.MoneyBucket
import com.xxjz.ledger.data.local.dao.TxnDao
import com.xxjz.ledger.domain.model.CategorySum
import com.xxjz.ledger.domain.model.DaySum
import com.xxjz.ledger.domain.model.Txn
import com.xxjz.ledger.domain.model.TxnType
import com.xxjz.ledger.domain.repository.AccountRepository
import com.xxjz.ledger.domain.repository.TxnRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TxnRepositoryImpl
    @Inject
    constructor(
        private val dao: TxnDao,
        private val categoryDao: CategoryDao,
        private val accountRepository: AccountRepository,
    ) : TxnRepository {
        override fun observeInRange(
            start: Long,
            end: Long,
        ): Flow<List<Txn>> = dao.observeInRange(start, end).map { list -> list.map { it.toModel() } }

        override suspend fun listInRange(
            start: Long,
            end: Long,
        ): List<Txn> = dao.listInRange(start, end).map { it.toModel() }

        override fun observeById(id: String): Flow<Txn?> = dao.observeById(id).map { it?.toModel() }

        override suspend fun byId(id: String): Txn? = dao.byId(id)?.toModel()

        override fun observeByAccount(accountId: String): Flow<List<Txn>> =
            dao.observeByAccount(accountId).map { list -> list.map { it.toModel() } }

        override fun observePendingReimbursement(): Flow<List<Txn>> =
            dao.observePendingReimbursement().map { list -> list.map { it.toModel() } }

        override suspend fun pendingReimbursement(): List<Txn> = dao.listPendingReimbursement().map { it.toModel() }

        override fun observeDeleted(): Flow<List<Txn>> = dao.observeDeleted().map { list -> list.map { it.toModel() } }

        override fun observeTotalCount(): Flow<Int> = dao.observeTotalCount()

        override fun observeAllForReport(): Flow<List<Txn>> = dao.observeAllForReport().map { list -> list.map { it.toModel() } }

        override suspend fun allForReport(): List<Txn> = dao.listAllIncludingDeleted().map { it.toModel() }

        override fun observeTotals(
            start: Long,
            end: Long,
        ): Flow<Pair<Long, Long>> =
            dao.observeSumByType(start, end).map { buckets ->
                val income = buckets.firstOrNull { it.key == TxnType.INCOME.name }?.total ?: 0L
                val expense = buckets.firstOrNull { it.key == TxnType.EXPENSE.name }?.total ?: 0L
                income to expense
            }

        override suspend fun totals(
            start: Long,
            end: Long,
        ): Pair<Long, Long> {
            val buckets = dao.sumByType(start, end)
            val income = buckets.firstOrNull { it.key == TxnType.INCOME.name }?.total ?: 0L
            val expense = buckets.firstOrNull { it.key == TxnType.EXPENSE.name }?.total ?: 0L
            return income to expense
        }

        override fun observeCategorySums(
            type: TxnType,
            start: Long,
            end: Long,
        ): Flow<List<CategorySum>> =
            combine(dao.observeSumByCategory(type.name, start, end), categoryDao.observeAll()) { buckets, cats ->
                val nameById = cats.associate { it.id to it.name }
                buckets.map { b ->
                    CategorySum(
                        categoryId = b.key,
                        categoryName = b.key?.let { nameById[it] } ?: "未分类",
                        total = b.total,
                        count = b.cnt,
                    )
                }
            }

        override fun observeDaySums(
            type: TxnType,
            fromKey: Int,
            toKey: Int,
        ): Flow<List<DaySum>> = dao.observeSumByDay(type.name, fromKey, toKey).map { toDaySums(it, type) }

        override suspend fun daySums(
            type: TxnType,
            fromKey: Int,
            toKey: Int,
        ): List<DaySum> = toDaySums(dao.sumByDay(type.name, fromKey, toKey), type)

        private fun toDaySums(
            buckets: List<MoneyBucket>,
            type: TxnType,
        ): List<DaySum> =
            buckets.mapNotNull { b ->
                val key = b.key?.toIntOrNull() ?: return@mapNotNull null
                if (type == TxnType.INCOME) {
                    DaySum(key, income = b.total, expense = 0, count = b.cnt)
                } else {
                    DaySum(key, income = 0, expense = b.total, count = b.cnt)
                }
            }

        override suspend fun search(
            keyword: String,
            type: TxnType?,
            minCents: Long,
            maxCents: Long,
            start: Long,
            end: Long,
        ): List<Txn> =
            dao.search(
                keyword = keyword.trim(),
                type = type?.name.orEmpty(),
                minAmount = minCents,
                maxAmount = maxCents,
                start = start,
                end = end,
            ).map { it.toModel() }

        override suspend fun refundedTotal(txnId: String): Long = dao.refundedTotal(txnId)

        override suspend fun accountTotals(
            accountId: String,
            start: Long,
            end: Long,
        ): Pair<Long, Long> {
            val buckets = dao.sumByAccount(accountId, start, end)
            val income = buckets.firstOrNull { it.key == TxnType.INCOME.name }?.total ?: 0L
            val expense = buckets.firstOrNull { it.key == TxnType.EXPENSE.name }?.total ?: 0L
            return income to expense
        }

        override suspend fun insert(txn: Txn) {
            dao.insert(txn.toEntity())
            // v1.8.0：转入/转出投资账户时自动累加/扣减累计成本（PRD D15「买入=转入、卖出=转出」）
            adjustInvestCost(txn, sign = 1)
        }

        override suspend fun update(txn: Txn) {
            // 改金额/改账户时先把旧版本对成本的影响撤销，再按新版本重算，避免成本越改越偏
            val before = dao.byId(txn.id)?.toModel()
            dao.update(txn.toEntity())
            if (before != null) adjustInvestCost(before, sign = -1)
            adjustInvestCost(txn, sign = 1)
        }

        /** 投资成本联动：转账进入投资账户 +金额，转出 −金额；其它类型不参与。 */
        private suspend fun adjustInvestCost(
            txn: Txn,
            sign: Int,
        ) {
            if (txn.type != TxnType.TRANSFER || txn.deletedAt != null) return
            // 转入：toAccountId 是投资账户 → 成本增加（资金变资产）
            txn.toAccountId?.let { accountRepository.adjustInvestCost(it, sign * txn.amount, txn.updatedAt) }
            // 转出：accountId 是投资账户 → 成本减少
            txn.accountId?.let { accountRepository.adjustInvestCost(it, -sign * txn.amount, txn.updatedAt) }
        }

        override suspend fun softDelete(
            id: String,
            at: Long,
        ) {
            val txn = dao.byId(id)?.toModel()
            dao.softDelete(id, at)
            // 删掉一笔投资相关的转账，成本要回退，否则市值/成本口径会永久失真
            if (txn != null) adjustInvestCost(txn, sign = -1)
        }

        override suspend fun restore(
            id: String,
            at: Long,
        ) {
            val txn = dao.byId(id)?.toModel()
            dao.restore(id, at)
            // 回收站还原：只有原本未删除的转账才需要把成本加回去
            if (txn != null && txn.deletedAt != null) adjustInvestCost(txn.copy(deletedAt = null), sign = 1)
        }

        override suspend fun hardDelete(id: String) {
            val txn = dao.byId(id)?.toModel()
            dao.hardDelete(id)
            if (txn != null) adjustInvestCost(txn, sign = -1)
        }

        override suspend fun setExcluded(
            id: String,
            excluded: Boolean,
            at: Long,
        ) = dao.setExcluded(id, excluded, at)

        override suspend fun setReimbursed(
            id: String,
            amount: Long,
            reimbursedAt: Long?,
            at: Long,
            pending: Boolean,
        ) = dao.setReimbursed(id, amount, reimbursedAt, at, pending)

        override suspend fun reimbursementOf(txnId: String): Txn? = dao.reimbursementOf(txnId)?.toModel()
    }
