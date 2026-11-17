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

    /**
     * 流水来源（v1.9.0）。余额不再是账户上的静态数字，而是「初始余额 + 转账流入 −
     * 转账流出 + 借贷操作侧」——否则借贷用例里每次操作都得手动改账户余额，
     * 测的就不是 UseCase 而是测试自己对余额的假设。需要真实账户时把它设上即可。
     */
    var txns: FakeTxnRepository? = null

    /** 变更信号，见类注释。 */
    private val version = MutableStateFlow(0)

    private fun effective(account: Account): Account {
        val source = txns ?: return account
        val live = source.items.values.filter { !it.isDeleted }
        var balance = account.initialBalance
        live.forEach { txn ->
            balance += deltaOf(txn, account)
        }
        return account.copy(balance = balance)
    }

    /**
     * 一笔流水对某个账户的影响。
     *
     * 口径与 `AccountDao.observeBalances()` 的聚合 SQL 一致：
     * - 流出侧（`accountId`）：普通流水减、借贷流水按 `debtAction` 定正负（借贷账户挂在这一侧）；
     * - 流入侧（`toAccountId`）：普通流水加；借贷流水在这里代表**资金账户**那一侧，
     *   符号与借贷侧相反，同样由 `debtAction` 决定。
     */
    private fun deltaOf(
        txn: com.xxjz.ledger.domain.model.Txn,
        account: Account,
    ): Long {
        var delta = 0L
        if (txn.accountId == account.id) {
            delta +=
                if (txn.debtAction == null) {
                    -txn.amount
                } else {
                    // 借出 / 还款 = 待收待还增加；收款 / 借入 / 坏账核销 = 减少
                    debtSign(txn) * txn.amount
                }
        }
        if (txn.toAccountId == account.id) {
            delta +=
                if (txn.debtAction == null) {
                    txn.amount
                } else {
                    // 借贷流水里 toAccountId 是资金账户，与借贷侧互为相反数
                    -debtSign(txn) * txn.amount
                }
        }
        return delta
    }

    /** 借贷账户一侧的正负号。 */
    private fun debtSign(txn: com.xxjz.ledger.domain.model.Txn): Long =
        when (txn.debtAction) {
            com.xxjz.ledger.domain.model.DebtAction.LEND_OUT,
            com.xxjz.ledger.domain.model.DebtAction.REPAY,
            -> 1L
            else -> -1L
        }

    private fun visible(): List<Account> = items.values.filter { !deletedAt.containsKey(it.id) }

    override fun observeVisible(): Flow<List<Account>> =
        version.map { items.values.filter { !it.hidden && !deletedAt.containsKey(it.id) }.map(::effective) }

    override fun observeAll(): Flow<List<Account>> = version.map { visible().map(::effective) }

    override fun observeWithBalances(): Flow<List<Account>> = observeAll()

    override suspend fun byId(id: String): Account? = items[id]?.let(::effective)

    override suspend fun balanceOf(id: String): Long = items[id]?.let { effective(it).balance } ?: 0L

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

    /** 账户合并（v1.9.0）：把源账户上的流水改挂到目标账户，口径与真实实现保持一致。 */
    override suspend fun moveTxnsTo(
        fromId: String,
        toId: String,
        at: Long,
    ) {
        if (fromId == toId) return
        val source = txns ?: return
        source.items.entries.forEach { (id, txn) ->
            if (txn.isDeleted) return@forEach
            source.items[id] =
                when {
                    txn.accountId == fromId && txn.toAccountId == fromId ->
                        txn.copy(accountId = toId, toAccountId = toId, updatedAt = at)
                    txn.accountId == fromId -> txn.copy(accountId = toId, updatedAt = at)
                    txn.toAccountId == fromId -> txn.copy(toAccountId = toId, updatedAt = at)
                    else -> return@forEach
                }
        }
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
            .filter { rule ->
                val endAt = rule.endAt
                !rule.paused && rule.nextRunAt <= now && (endAt == null || rule.nextRunAt <= endAt)
            }
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

// v1.9.0 起借贷不再是独立仓库：一个借贷账户就是一个 Account（type = DEBT），
// 余额由 AccountDao 聚合流水算出，用 FakeAccountRepository 即可，见 DebtUseCasesTest。

/** 分期仓库的内存实现（v1.9.0）。 */
class FakeInstallmentRepository : com.xxjz.ledger.domain.repository.InstallmentRepository {
    val items = linkedMapOf<String, com.xxjz.ledger.domain.model.Installment>()

    private val version = MutableStateFlow(0)

    override fun observeByAccount(accountId: String): Flow<List<com.xxjz.ledger.domain.model.Installment>> =
        version.map { items.values.filter { it.accountId == accountId } }

    override fun observeAll(): Flow<List<com.xxjz.ledger.domain.model.Installment>> = version.map { items.values.toList() }

    override suspend fun listByAccount(accountId: String): List<com.xxjz.ledger.domain.model.Installment> =
        items.values.filter { it.accountId == accountId }

    override suspend fun byId(id: String): com.xxjz.ledger.domain.model.Installment? = items[id]

    override suspend fun upsert(plan: com.xxjz.ledger.domain.model.Installment) {
        items[plan.id] = plan
        version.value += 1
    }

    override suspend fun delete(id: String) {
        items.remove(id)
        version.value += 1
    }
}
