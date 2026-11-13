package com.xxjz.ledger.data.repository

import androidx.room.withTransaction
import com.xxjz.ledger.data.local.dao.AccountDao
import com.xxjz.ledger.data.local.dao.BudgetDao
import com.xxjz.ledger.data.local.dao.CategoryDao
import com.xxjz.ledger.data.local.dao.DebtDao
import com.xxjz.ledger.data.local.dao.MerchantDao
import com.xxjz.ledger.data.local.dao.MoneyBucket
import com.xxjz.ledger.data.local.dao.RecurringDao
import com.xxjz.ledger.data.local.dao.TagDao
import com.xxjz.ledger.data.local.dao.TemplateDao
import com.xxjz.ledger.data.local.dao.TxnDao
import com.xxjz.ledger.data.local.entity.AccountEntity
import com.xxjz.ledger.data.local.entity.BudgetEntity
import com.xxjz.ledger.data.local.entity.CategoryEntity
import com.xxjz.ledger.data.local.entity.DebtEntity
import com.xxjz.ledger.data.local.entity.MerchantEntity
import com.xxjz.ledger.data.local.entity.RecurringRuleEntity
import com.xxjz.ledger.data.local.entity.TagEntity
import com.xxjz.ledger.data.local.entity.TemplateEntity
import com.xxjz.ledger.data.local.entity.TxnEntity
import com.xxjz.ledger.data.local.entity.TxnTagEntity
import com.xxjz.ledger.data.local.seed.SeedData
import com.xxjz.ledger.domain.model.Account
import com.xxjz.ledger.domain.model.AccountType
import com.xxjz.ledger.domain.model.Budget
import com.xxjz.ledger.domain.model.BudgetPeriod
import com.xxjz.ledger.domain.model.BudgetScope
import com.xxjz.ledger.domain.model.Category
import com.xxjz.ledger.domain.model.CategorySum
import com.xxjz.ledger.domain.model.DaySum
import com.xxjz.ledger.domain.model.Debt
import com.xxjz.ledger.domain.model.DebtDirection
import com.xxjz.ledger.domain.model.Merchant
import com.xxjz.ledger.domain.model.RecurringPeriod
import com.xxjz.ledger.domain.model.RecurringRule
import com.xxjz.ledger.domain.model.Tag
import com.xxjz.ledger.domain.model.Template
import com.xxjz.ledger.domain.model.Txn
import com.xxjz.ledger.domain.model.TxnType
import com.xxjz.ledger.domain.repository.AccountRepository
import com.xxjz.ledger.domain.repository.BudgetRepository
import com.xxjz.ledger.domain.repository.CategoryRepository
import com.xxjz.ledger.domain.repository.DebtRepository
import com.xxjz.ledger.domain.repository.MerchantRepository
import com.xxjz.ledger.domain.repository.RecurringRepository
import com.xxjz.ledger.domain.repository.TagRepository
import com.xxjz.ledger.domain.repository.TemplateRepository
import com.xxjz.ledger.domain.repository.TxnRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

// ---------- Entity ↔ Model ----------

internal fun TxnEntity.toModel(): Txn =
    Txn(
        id = id,
        type = TxnType.valueOf(type),
        amount = amount,
        categoryId = categoryId,
        accountId = accountId,
        toAccountId = toAccountId,
        fee = fee,
        dateTime = dateTime,
        dateKey = dateKey,
        note = note,
        memo = memo,
        merchantId = merchantId,
        merchantName = merchantName,
        currency = currency,
        ledgerId = ledgerId,
        excludedFromStats = excludedFromStats,
        pendingReimbursement = pendingReimbursement,
        reimbursedAmount = reimbursedAmount,
        reimbursedAt = reimbursedAt,
        refundOfTxnId = refundOfTxnId,
        parentTxnId = parentTxnId,
        debtId = debtId,
        deletedAt = deletedAt,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

internal fun Txn.toEntity(): TxnEntity =
    TxnEntity(
        id = id,
        type = type.name,
        amount = amount,
        categoryId = categoryId,
        accountId = accountId,
        toAccountId = toAccountId,
        fee = fee,
        dateTime = dateTime,
        dateKey = dateKey,
        note = note,
        memo = memo,
        merchantId = merchantId,
        merchantName = merchantName,
        currency = currency,
        ledgerId = ledgerId,
        excludedFromStats = excludedFromStats,
        pendingReimbursement = pendingReimbursement,
        reimbursedAmount = reimbursedAmount,
        reimbursedAt = reimbursedAt,
        refundOfTxnId = refundOfTxnId,
        parentTxnId = parentTxnId,
        debtId = debtId,
        deletedAt = deletedAt,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

internal fun AccountEntity.toModel(balance: Long = 0): Account =
    Account(
        id = id,
        name = name,
        type = AccountType.valueOf(type),
        initialBalance = initialBalance,
        icon = icon,
        color = color,
        cardLimit = cardLimit,
        billDay = billDay,
        repayDay = repayDay,
        hidden = hidden,
        sortOrder = sortOrder,
        balance = balance,
    )

internal fun Account.toEntity(): AccountEntity =
    AccountEntity(
        id = id,
        name = name,
        type = type.name,
        initialBalance = initialBalance,
        icon = icon,
        color = color,
        cardLimit = cardLimit,
        billDay = billDay,
        repayDay = repayDay,
        hidden = hidden,
        sortOrder = sortOrder,
    )

internal fun CategoryEntity.toModel(): Category =
    Category(
        id = id,
        name = name,
        parentId = parentId,
        type = TxnType.valueOf(type),
        icon = icon,
        color = color,
        sortOrder = sortOrder,
        hidden = hidden,
        pinned = pinned,
        usageCount = usageCount,
    )

internal fun Category.toEntity(): CategoryEntity =
    CategoryEntity(
        id = id,
        name = name,
        parentId = parentId,
        type = type.name,
        icon = icon,
        color = color,
        sortOrder = sortOrder,
        hidden = hidden,
        pinned = pinned,
        usageCount = usageCount,
    )

internal fun TagEntity.toModel(): Tag = Tag(id = id, name = name, color = color, usageCount = usageCount)

internal fun TemplateEntity.toModel(): Template =
    Template(
        id = id,
        name = name,
        type = TxnType.valueOf(type),
        amount = amount,
        categoryId = categoryId,
        accountId = accountId,
        toAccountId = toAccountId,
        note = note,
        sortOrder = sortOrder,
    )

internal fun Template.toEntity(): TemplateEntity =
    TemplateEntity(
        id = id,
        name = name,
        type = type.name,
        amount = amount,
        categoryId = categoryId,
        accountId = accountId,
        toAccountId = toAccountId,
        note = note,
        sortOrder = sortOrder,
    )

internal fun BudgetEntity.toModel(): Budget =
    Budget(
        id = id,
        monthKey = monthKey,
        name = name,
        period = BudgetPeriod.valueOf(period),
        scope = BudgetScope.valueOf(scope),
        categoryIds = categoryIds.split(BUDGET_CATEGORY_SEP).filter { it.isNotBlank() },
        amount = amount,
        enabled = enabled,
        deletedAt = deletedAt,
        sortOrder = sortOrder,
    )

internal fun Budget.toEntity(): BudgetEntity =
    BudgetEntity(
        id = id,
        monthKey = monthKey,
        name = name,
        period = period.name,
        scope = scope.name,
        categoryIds = categoryIds.filter { it.isNotBlank() }.joinToString(BUDGET_CATEGORY_SEP),
        amount = amount,
        enabled = enabled,
        deletedAt = deletedAt,
        sortOrder = sortOrder,
    )

/** 预算分类 id 列表的存储分隔符。 */
private const val BUDGET_CATEGORY_SEP = ","

internal fun DebtEntity.toModel(repaid: Long = 0): Debt =
    Debt(
        id = id,
        direction = DebtDirection.valueOf(direction),
        counterparty = counterparty,
        principal = principal,
        accountId = accountId,
        startAt = startAt,
        dueAt = dueAt,
        note = note,
        settled = settled,
        writtenOff = writtenOff,
        repaid = repaid,
    )

internal fun Debt.toEntity(): DebtEntity =
    DebtEntity(
        id = id,
        direction = direction.name,
        counterparty = counterparty,
        principal = principal,
        accountId = accountId,
        startAt = startAt,
        dueAt = dueAt,
        note = note,
        settled = settled,
        writtenOff = writtenOff,
    )

internal fun RecurringRuleEntity.toModel(): RecurringRule =
    RecurringRule(
        id = id,
        type = TxnType.valueOf(type),
        amount = amount,
        categoryId = categoryId,
        accountId = accountId,
        toAccountId = toAccountId,
        note = note,
        period = RecurringPeriod.valueOf(period),
        interval = interval,
        nextRunAt = nextRunAt,
        endAt = endAt,
        maxTimes = maxTimes,
        runTimes = runTimes,
        paused = paused,
    )

internal fun RecurringRule.toEntity(): RecurringRuleEntity =
    RecurringRuleEntity(
        id = id,
        type = type.name,
        amount = amount,
        categoryId = categoryId,
        accountId = accountId,
        toAccountId = toAccountId,
        note = note,
        period = period.name,
        interval = interval,
        nextRunAt = nextRunAt,
        endAt = endAt,
        maxTimes = maxTimes,
        runTimes = runTimes,
        paused = paused,
    )

// ---------- 实现 ----------

@Singleton
class TxnRepositoryImpl
    @Inject
    constructor(
        private val dao: TxnDao,
        private val categoryDao: CategoryDao,
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

        override suspend fun insert(txn: Txn) = dao.insert(txn.toEntity())

        override suspend fun update(txn: Txn) = dao.update(txn.toEntity())

        override suspend fun softDelete(
            id: String,
            at: Long,
        ) = dao.softDelete(id, at)

        override suspend fun restore(
            id: String,
            at: Long,
        ) = dao.restore(id, at)

        override suspend fun hardDelete(id: String) = dao.hardDelete(id)

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

@Singleton
class AccountRepositoryImpl
    @Inject
    constructor(
        private val db: com.xxjz.ledger.data.local.db.AppDatabase,
        private val dao: AccountDao,
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
    }

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
            val tag = Tag(id = SeedData.newId("tag"), name = name.trim())
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
            val merchant = MerchantEntity(id = SeedData.newId("merchant"), name = trimmed, usageCount = 1)
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

@Singleton
class DebtRepositoryImpl
    @Inject
    constructor(
        private val dao: DebtDao,
    ) : DebtRepository {
        override fun observeAll(): Flow<List<Debt>> = dao.observeAll().map { list -> list.map { it.toModel() } }

        override suspend fun all(): List<Debt> = dao.listAll().map { it.toModel(dao.repaidTotal(it.id)) }

        override suspend fun byId(id: String): Debt? {
            val e = dao.byId(id) ?: return null
            return e.toModel(dao.repaidTotal(debtId = id))
        }

        override suspend fun repaidOf(debtId: String): Long = dao.repaidTotal(debtId)

        override fun observeRepaidOf(debtId: String): Flow<Long> = dao.observeRepaidTotal(debtId)

        override suspend fun upsert(debt: Debt) = dao.upsert(debt.toEntity())

        override suspend fun delete(id: String) = dao.delete(id)
    }

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
