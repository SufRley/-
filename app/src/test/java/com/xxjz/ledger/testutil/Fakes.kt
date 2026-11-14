package com.xxjz.ledger.testutil

import com.xxjz.ledger.domain.model.Account
import com.xxjz.ledger.domain.model.Budget
import com.xxjz.ledger.domain.model.Category
import com.xxjz.ledger.domain.model.CategorySum
import com.xxjz.ledger.domain.model.DaySum
import com.xxjz.ledger.domain.model.Merchant
import com.xxjz.ledger.domain.model.Tag
import com.xxjz.ledger.domain.model.Template
import com.xxjz.ledger.domain.model.Txn
import com.xxjz.ledger.domain.model.TxnType
import com.xxjz.ledger.domain.repository.AccountRepository
import com.xxjz.ledger.domain.repository.BudgetRepository
import com.xxjz.ledger.domain.repository.CategoryRepository
import com.xxjz.ledger.domain.repository.MerchantRepository
import com.xxjz.ledger.domain.repository.TagRepository
import com.xxjz.ledger.domain.repository.TemplateRepository
import com.xxjz.ledger.domain.repository.TxnRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * 单测用的内存仓库。
 *
 * 只实现被测用例真正依赖的语义，其余查询返回空结果；写入/软删/恢复/退款累计都按真实规则维护，
 * 这样用例层测试不依赖 Room，DAO 的真实 SQL 由 Robolectric 集成测试单独覆盖。
 *
 * 对外暴露的 Flow 都接在 [version] 这个 `MutableStateFlow` 上：fake 里没有 Room 的
 * `InvalidationTracker`，如果直接 `flowOf(...)` 只会在收集那一刻拍一张快照，
 * ViewModel 里 `combine` 出来的列表就会永远停在初始化时的空列表。
 */
class FakeTxnRepository : TxnRepository {
    val items = linkedMapOf<String, Txn>()

    /** 变更信号，见类注释。 */
    private val version = MutableStateFlow(0)

    /**
     * 测试直接往 [items] 里塞数据时用这个：绕开仓库接口，但仍然发一次变更信号，
     * 否则 `observeDeleted()` 这类接在 [version] 上的 Flow 永远停在初始化时的空列表。
     */
    fun put(
        txn: Txn,
        notify: Boolean = true,
    ) {
        items[txn.id] = txn
        if (notify) version.value += 1
    }

    /** 测试直接软删时用这个（同样补一次变更信号）。 */
    fun markDeleted(
        id: String,
        at: Long,
    ) {
        items[id]?.let { items[id] = it.copy(deletedAt = at, updatedAt = at) }
        version.value += 1
    }

    override fun observeInRange(
        start: Long,
        end: Long,
    ): Flow<List<Txn>> = version.map { items.values.filter { !it.isDeleted && it.dateTime in start..end } }

    override suspend fun listInRange(
        start: Long,
        end: Long,
    ): List<Txn> = items.values.filter { !it.isDeleted && it.dateTime in start..end }

    override fun observeById(id: String): Flow<Txn?> = flowOf(items[id])

    override suspend fun byId(id: String): Txn? = items[id]

    override fun observeByAccount(accountId: String): Flow<List<Txn>> =
        flowOf(items.values.filter { !it.isDeleted && (it.accountId == accountId || it.toAccountId == accountId) })

    override fun observePendingReimbursement(): Flow<List<Txn>> =
        flowOf(items.values.filter { !it.isDeleted && it.pendingReimbursement && it.type == TxnType.EXPENSE })

    override suspend fun pendingReimbursement(): List<Txn> =
        items.values.filter { !it.isDeleted && it.pendingReimbursement && it.type == TxnType.EXPENSE }

    override fun observeDeleted(): Flow<List<Txn>> = version.map { items.values.filter { it.isDeleted } }

    override fun observeTotalCount(): Flow<Int> = version.map { items.size }

    override fun observeTotals(
        start: Long,
        end: Long,
    ): Flow<Pair<Long, Long>> = version.map { sumInRange(start, end) }

    override suspend fun totals(
        start: Long,
        end: Long,
    ): Pair<Long, Long> = sumInRange(start, end)

    private fun sumInRange(
        start: Long,
        end: Long,
    ): Pair<Long, Long> {
        val counted = items.values.filter { !it.isDeleted && !it.excludedFromStats && it.dateTime in start..end }
        val income = counted.filter { it.type == TxnType.INCOME }.sumOf { it.amount }
        val expense = counted.filter { it.type == TxnType.EXPENSE }.sumOf { it.amount }
        return income to expense
    }

    override fun observeCategorySums(
        type: TxnType,
        start: Long,
        end: Long,
    ): Flow<List<CategorySum>> = flowOf(emptyList())

    override fun observeDaySums(
        type: TxnType,
        fromKey: Int,
        toKey: Int,
    ): Flow<List<DaySum>> = flowOf(emptyList())

    override suspend fun daySums(
        type: TxnType,
        fromKey: Int,
        toKey: Int,
    ): List<DaySum> = emptyList()

    override suspend fun accountTotals(
        accountId: String,
        start: Long,
        end: Long,
    ): Pair<Long, Long> {
        val list = items.values.filter { !it.isDeleted && !it.excludedFromStats && it.accountId == accountId && it.dateTime in start..end }
        return list.filter { it.type == TxnType.INCOME }.sumOf { it.amount } to
            list.filter { it.type == TxnType.EXPENSE }.sumOf { it.amount }
    }

    override suspend fun refundedTotal(txnId: String): Long =
        items.values.filter { !it.isDeleted && it.refundOfTxnId == txnId }.sumOf { it.amount }

    /** 与 TxnDao.search 语义一致：关键词命中备注/商户、类型、金额闭区间、时间闭区间、按时间倒序。 */
    override suspend fun search(
        keyword: String,
        type: TxnType?,
        minCents: Long,
        maxCents: Long,
        start: Long,
        end: Long,
    ): List<Txn> {
        val kw = keyword.trim()
        return items.values
            .filter { !it.isDeleted }
            .filter {
                kw.isEmpty() ||
                    it.note.contains(kw, ignoreCase = true) ||
                    (it.merchantName?.contains(kw, ignoreCase = true) == true)
            }.filter { type == null || it.type == type }
            .filter { it.amount in minCents..maxCents }
            .filter { it.dateTime in start..end }
            .sortedWith(compareByDescending<Txn> { it.dateTime }.thenByDescending { it.createdAt })
    }

    override suspend fun insert(txn: Txn) {
        items[txn.id] = txn
        version.value += 1
    }

    override suspend fun update(txn: Txn) {
        items[txn.id] = txn
        version.value += 1
    }

    override suspend fun softDelete(
        id: String,
        at: Long,
    ) {
        items[id]?.let { items[id] = it.copy(deletedAt = at, updatedAt = at) }
        version.value += 1
    }

    override suspend fun restore(
        id: String,
        at: Long,
    ) {
        items[id]?.let { items[id] = it.copy(deletedAt = null, updatedAt = at) }
        version.value += 1
    }

    override suspend fun hardDelete(id: String) {
        items.remove(id)
        version.value += 1
    }

    override suspend fun setExcluded(
        id: String,
        excluded: Boolean,
        at: Long,
    ) {
        items[id]?.let { items[id] = it.copy(excludedFromStats = excluded, updatedAt = at) }
    }

    override suspend fun setReimbursed(
        id: String,
        amount: Long,
        reimbursedAt: Long?,
        at: Long,
        pending: Boolean,
    ) {
        items[id]?.let {
            items[id] = it.copy(reimbursedAmount = amount, reimbursedAt = reimbursedAt, pendingReimbursement = pending, updatedAt = at)
        }
    }

    override suspend fun reimbursementOf(txnId: String): Txn? =
        items.values.firstOrNull { it.parentTxnId == txnId && it.type == TxnType.INCOME && !it.isDeleted }
}

class FakeCategoryRepository : CategoryRepository {
    val usage = mutableMapOf<String, Int>()
    var categories: List<Category> = emptyList()

    /** 测试用：直接登记某分类被多少笔流水引用。 */
    val txnUsage = mutableMapOf<String, Int>()

    private val version = MutableStateFlow(0)

    override fun observeAll(): Flow<List<Category>> = version.map { categories }

    override suspend fun listAll(): List<Category> = categories

    override suspend fun byId(id: String): Category? = categories.firstOrNull { it.id == id }

    override suspend fun tree(type: TxnType): List<Pair<Category, List<Category>>> {
        val ofType = categories.filter { it.type == type }
        val children = ofType.filter { it.parentId != null }.groupBy { it.parentId }
        return ofType.filter { it.parentId == null }.map { it to (children[it.id] ?: emptyList()) }
    }

    override suspend fun frequent(
        type: TxnType,
        limit: Int,
    ): List<Category> = categories.filter { it.type == type }.sortedByDescending { it.usageCount }.take(limit)

    override suspend fun increaseUsage(id: String) {
        usage[id] = (usage[id] ?: 0) + 1
    }

    override suspend fun upsert(category: Category) {
        categories = categories.filter { it.id != category.id } + category
        version.value += 1
    }

    /** 该分类（含一级子类）下的流水条数，与真实实现的语义一致。 */
    override suspend fun txnCountOf(id: String): Int {
        val ids = setOf(id) + categories.filter { it.parentId == id }.map { it.id }
        return txnUsage.filterKeys { it in ids }.values.sum()
    }

    override suspend fun setHidden(
        id: String,
        hidden: Boolean,
    ) {
        categories = categories.map { if (it.id == id) it.copy(hidden = hidden) else it }
        version.value += 1
    }

    override suspend fun delete(id: String) {
        categories = categories.filter { it.id != id }
        version.value += 1
    }
}

class FakeAccountRepository : AccountRepository {
    val items = linkedMapOf<String, Account>()

    /** 变更信号，见类注释。 */
    private val version = MutableStateFlow(0)

    private fun visible(): List<Account> = items.values.filter { !deletedAt.containsKey(it.id) }

    override fun observeVisible(): Flow<List<Account>> = version.map { items.values.filter { !it.hidden && !deletedAt.containsKey(it.id) } }

    override fun observeAll(): Flow<List<Account>> = version.map { visible() }

    override fun observeWithBalances(): Flow<List<Account>> = observeAll()

    override suspend fun byId(id: String): Account? = items[id]

    override suspend fun balanceOf(id: String): Long = items[id]?.balance ?: 0L

    override suspend fun upsert(account: Account) {
        items[account.id] = account
        version.value += 1
    }

    /** 测试直接软删账户时用这个（补一次变更信号）。 */
    fun markDeleted(
        id: String,
        at: Long,
    ) {
        deletedAt[id] = at
        version.value += 1
    }

    override suspend fun count(): Int = items.count { !deletedAt.containsKey(it.key) }

    override fun observeDeleted(): Flow<List<Account>> = version.map { items.values.filter { deletedAt.containsKey(it.id) } }

    override suspend fun deleteWithTxns(
        id: String,
        at: Long,
    ) {
        deletedAt[id] = at
        version.value += 1
    }

    override suspend fun restore(
        id: String,
        at: Long,
    ) {
        deletedAt.remove(id)
        version.value += 1
    }

    override suspend fun purge(id: String) {
        deletedAt.remove(id)
        items.remove(id)
        version.value += 1
    }

    override suspend fun setHidden(
        id: String,
        hidden: Boolean,
    ) {
        items[id]?.let { items[id] = it.copy(hidden = hidden) }
    }

    override suspend fun setSortOrder(
        id: String,
        order: Int,
    ) {
        items[id]?.let { items[id] = it.copy(sortOrder = order) }
    }

    override suspend fun txnCount(id: String): Int = txnCounts[id] ?: 0

    val txnCounts = mutableMapOf<String, Int>()
    val deletedAt = mutableMapOf<String, Long>()

    /** 改市值时记录的事件，供「资产变化记录」相关测试断言。 */
    val marketValueEvents = mutableListOf<Pair<String, Long>>()

    override suspend fun adjustInvestCost(
        id: String,
        deltaCents: Long,
        at: Long,
    ) {
        val current = items[id] ?: return
        if (current.type != com.xxjz.ledger.domain.model.AccountType.INVEST) return
        items[id] = current.copy(costCents = (current.costCents + deltaCents).coerceAtLeast(0))
        version.value += 1
    }

    override suspend fun updateMarketValue(
        id: String,
        marketValueCents: Long,
        at: Long,
    ) {
        val current = items[id] ?: return
        if (current.type != com.xxjz.ledger.domain.model.AccountType.INVEST) return
        marketValueEvents += id to marketValueCents
        items[id] = current.copy(marketValueCents = marketValueCents.coerceAtLeast(0))
        version.value += 1
    }
}

/** 标签仓库的内存实现（M9 管理页用）。 */
class FakeTagRepository : TagRepository {
    val items = linkedMapOf<String, Tag>()
    private val version = MutableStateFlow(0)
    private var seq = 0

    private val links = mutableMapOf<String, MutableList<String>>()

    override fun observeAll(): Flow<List<Tag>> = version.map { items.values.toList() }

    override suspend fun listAll(): List<Tag> = items.values.toList()

    override fun observeTagIdsOf(txnId: String): Flow<List<String>> = version.map { links[txnId].orEmpty().toList() }

    override suspend fun upsert(tag: Tag): Tag {
        items[tag.id] = tag
        version.value += 1
        return tag
    }

    override suspend fun findOrCreate(name: String): Tag {
        items.values.firstOrNull { it.name == name.trim() }?.let { return it }
        seq += 1
        return upsert(Tag(id = "tag$seq", name = name.trim()))
    }

    override suspend fun setTxnTags(
        txnId: String,
        tagIds: List<String>,
    ) {
        links[txnId] = tagIds.toMutableList()
        version.value += 1
    }

    override suspend fun tagIdsOf(txnId: String): List<String> = links[txnId].orEmpty().toList()

    override suspend fun delete(id: String) {
        items.remove(id)
        links.values.forEach { it.remove(id) }
        version.value += 1
    }

    /** 测试用：某条流水身上还挂着哪些标签。 */
    fun linksOf(txnId: String): List<String> = links[txnId].orEmpty().toList()
}

/** 商家仓库的内存实现（M9 管理页用）。 */
class FakeMerchantRepository : MerchantRepository {
    val items = linkedMapOf<String, Merchant>()
    private var seq = 0

    override suspend fun suggest(limit: Int): List<Merchant> = items.values.sortedByDescending { it.usageCount }.take(limit)

    override suspend fun findOrCreate(name: String): Merchant {
        val trimmed = name.trim()
        items.values.firstOrNull { it.name == trimmed }?.let { row ->
            val next = row.copy(usageCount = row.usageCount + 1)
            items[next.id] = next
            return next
        }
        seq += 1
        val created = Merchant(id = "m$seq", name = trimmed, usageCount = 1)
        items[created.id] = created
        return created
    }

    override suspend fun listAll(): List<Merchant> = items.values.sortedByDescending { it.usageCount }

    override suspend fun rename(
        id: String,
        name: String,
    ) {
        items[id]?.let { items[id] = it.copy(name = name.trim()) }
    }

    override suspend fun delete(id: String) {
        items.remove(id)
    }
}

class FakeTemplateRepository : TemplateRepository {
    val items = linkedMapOf<String, Template>()

    override fun observeAll(): Flow<List<Template>> = flowOf(items.values.sortedBy { it.sortOrder })

    override suspend fun byId(id: String): Template? = items[id]

    override suspend fun upsert(template: Template) {
        items[template.id] = template
    }

    override suspend fun delete(id: String) {
        items.remove(id)
    }
}

/** 预算仓库的内存实现：按 (id, monthKey) 存放，行为与 Room 版本一致。 */
class FakeBudgetRepository : BudgetRepository {
    val rows = linkedMapOf<Pair<String, Int>, Budget>()
    private val version = MutableStateFlow(0L)

    /** 直接塞一条预算，测试用。 */
    fun put(budget: Budget) {
        rows[budget.id to budget.monthKey] = budget
        version.value += 1
    }

    private fun snapshot(monthKey: Int): List<Budget> =
        rows.values.filter { it.monthKey == monthKey }.sortedWith(compareBy({ it.sortOrder }, { it.id }))

    private fun snapshotLatestBefore(monthKey: Int): List<Budget> {
        val latest = rows.values.filter { it.monthKey < monthKey }.maxOfOrNull { it.monthKey } ?: return emptyList()
        return snapshot(latest)
    }

    override fun observeMonthRows(monthKey: Int): Flow<List<Budget>> = version.map { snapshot(monthKey) }

    override fun observeLatestBefore(monthKey: Int): Flow<List<Budget>> = version.map { snapshotLatestBefore(monthKey) }

    override suspend fun monthRows(monthKey: Int): List<Budget> = snapshot(monthKey)

    override suspend fun latestBefore(monthKey: Int): List<Budget> = snapshotLatestBefore(monthKey)

    override suspend fun byId(
        id: String,
        monthKey: Int,
    ): Budget? = rows[id to monthKey]

    override suspend fun upsert(budget: Budget) = put(budget)

    override suspend fun upsertAll(list: List<Budget>) {
        list.forEach { rows[it.id to it.monthKey] = it }
        version.value += 1
    }

    override suspend fun softDelete(
        id: String,
        monthKey: Int,
        at: Long,
    ) {
        rows[id to monthKey]?.let {
            rows[id to monthKey] = it.copy(deletedAt = at)
            version.value += 1
        }
    }

    override suspend fun restore(
        id: String,
        monthKey: Int,
    ) {
        rows[id to monthKey]?.let {
            rows[id to monthKey] = it.copy(deletedAt = null)
            version.value += 1
        }
    }

    override fun observeDeleted(): Flow<List<Budget>> =
        version.map { rows.values.filter { it.deletedAt != null }.sortedByDescending { it.deletedAt } }

    override suspend fun setEnabled(
        id: String,
        monthKey: Int,
        enabled: Boolean,
    ) {
        rows[id to monthKey]?.let {
            rows[id to monthKey] = it.copy(enabled = enabled)
            version.value += 1
        }
    }

    override suspend fun delete(
        id: String,
        monthKey: Int,
    ) {
        rows.remove(id to monthKey)
        version.value += 1
    }
}

/** 周期规则仓库的内存实现：`due` 与真实 SQL 同语义（未暂停 + 未到期不返回 + 结束日过滤）。 */
class FakeRecurringRepository : com.xxjz.ledger.domain.repository.RecurringRepository {
    val items = linkedMapOf<String, com.xxjz.ledger.domain.model.RecurringRule>()
    private val version = MutableStateFlow(0L)

    fun put(rule: com.xxjz.ledger.domain.model.RecurringRule) {
        items[rule.id] = rule
        version.value += 1
    }

    override fun observeAll(): Flow<List<com.xxjz.ledger.domain.model.RecurringRule>> =
        version.map { items.values.sortedBy { it.nextRunAt } }

    override suspend fun due(now: Long): List<com.xxjz.ledger.domain.model.RecurringRule> =
        items.values
            .filter { !it.paused && it.nextRunAt <= now && (it.endAt == null || it.nextRunAt <= it.endAt) }
            .sortedBy { it.nextRunAt }

    override suspend fun byId(id: String): com.xxjz.ledger.domain.model.RecurringRule? = items[id]

    override suspend fun upsert(rule: com.xxjz.ledger.domain.model.RecurringRule) = put(rule)

    override suspend fun setPaused(
        id: String,
        paused: Boolean,
        nextRunAt: Long,
    ) {
        items[id]?.let {
            items[id] = it.copy(paused = paused, nextRunAt = nextRunAt)
            version.value += 1
        }
    }

    override suspend fun advance(
        id: String,
        nextRunAt: Long,
        runTimes: Int,
        paused: Boolean,
    ) {
        items[id]?.let {
            items[id] = it.copy(nextRunAt = nextRunAt, runTimes = runTimes, paused = paused)
            version.value += 1
        }
    }

    override suspend fun delete(id: String) {
        items.remove(id)
        version.value += 1
    }
}

/** 借贷仓库的内存实现；剩余额按「本金 - 已还」计算，与 DebtDao 聚合一致。 */
class FakeDebtRepository(
    private val txnRepository: FakeTxnRepository,
) : com.xxjz.ledger.domain.repository.DebtRepository {
    val items = linkedMapOf<String, com.xxjz.ledger.domain.model.Debt>()

    fun put(debt: com.xxjz.ledger.domain.model.Debt) {
        items[debt.id] = debt
    }

    private fun repaid(debtId: String): Long =
        txnRepository.items.values.filter { !it.isDeleted && it.debtId == debtId }.sumOf { it.amount }

    private fun withRepaid(debt: com.xxjz.ledger.domain.model.Debt) = debt.copy(repaid = repaid(debt.id))

    override fun observeAll(): Flow<List<com.xxjz.ledger.domain.model.Debt>> = flowOf(items.values.map { withRepaid(it) })

    override suspend fun all(): List<com.xxjz.ledger.domain.model.Debt> = items.values.map { withRepaid(it) }

    override suspend fun byId(id: String): com.xxjz.ledger.domain.model.Debt? = items[id]?.let { withRepaid(it) }

    override suspend fun repaidOf(debtId: String): Long = repaid(debtId)

    override fun observeRepaidOf(debtId: String): Flow<Long> = flowOf(repaid(debtId))

    override suspend fun upsert(debt: com.xxjz.ledger.domain.model.Debt) {
        items[debt.id] = debt.copy(repaid = 0)
    }

    override suspend fun delete(id: String) {
        items.remove(id)
    }
}

/**
 * 借贷仓库的内存实现（v1.8.0 专用）：额外实现 [observeOutstanding]。
 *
 * 用独立的类而不是改 [FakeDebtRepository]，是因为那个 Fake 被多处既有用例依赖，
 * 加接口默认方法会改变它们的行为；这里只补账户页入口卡需要的那条流。
 */
class FakeDebtRepositoryOutstanding(
    private val inner: FakeDebtRepository,
) : com.xxjz.ledger.domain.repository.DebtRepository by inner {
    override fun observeOutstanding(): Flow<List<Pair<String, Long>>> =
        inner.observeAll().map { list ->
            list.filter {
                !it.settled && !it.writtenOff && it.direction == com.xxjz.ledger.domain.model.DebtDirection.BORROW &&
                    it.remaining > 0
            }.map { it.id to it.remaining }
        }
}

/** SAF 文件通道的内存实现：用 Uri 当 key 存字节，测试里可以直接读出写了什么。 */
class FakeDocumentIo : com.xxjz.ledger.data.io.DocumentIo {
    val files = mutableMapOf<String, ByteArray>()
    var failOnWrite = false
    var displayNameOverride = ""

    override fun openInput(uri: String): java.io.InputStream = java.io.ByteArrayInputStream(files[uri] ?: error("文件不存在"))

    override fun openOutput(uri: String): java.io.OutputStream {
        if (failOnWrite) error("模拟写入失败")
        return object : java.io.ByteArrayOutputStream() {
            override fun close() {
                super.close()
                files[uri] = toByteArray()
            }
        }
    }

    override fun displayName(uri: String): String = displayNameOverride.ifBlank { uri.substringAfterLast('/') }
}
