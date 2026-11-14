package com.xxjz.ledger.domain.repository

import com.xxjz.ledger.domain.model.Account
import com.xxjz.ledger.domain.model.Budget
import com.xxjz.ledger.domain.model.Category
import com.xxjz.ledger.domain.model.CategorySum
import com.xxjz.ledger.domain.model.DaySum
import com.xxjz.ledger.domain.model.Debt
import com.xxjz.ledger.domain.model.Merchant
import com.xxjz.ledger.domain.model.RecurringRule
import com.xxjz.ledger.domain.model.Tag
import com.xxjz.ledger.domain.model.Template
import com.xxjz.ledger.domain.model.Txn
import com.xxjz.ledger.domain.model.TxnType
import kotlinx.coroutines.flow.Flow

/** 账目仓库。 */
interface TxnRepository {
    fun observeInRange(
        start: Long,
        end: Long,
    ): Flow<List<Txn>>

    suspend fun listInRange(
        start: Long,
        end: Long,
    ): List<Txn>

    fun observeById(id: String): Flow<Txn?>

    suspend fun byId(id: String): Txn?

    fun observeByAccount(accountId: String): Flow<List<Txn>>

    fun observePendingReimbursement(): Flow<List<Txn>>

    suspend fun pendingReimbursement(): List<Txn>

    fun observeDeleted(): Flow<List<Txn>>

    fun observeTotalCount(): Flow<Int>

    /** 区间内的收入与支出（分）。 */
    fun observeTotals(
        start: Long,
        end: Long,
    ): Flow<Pair<Long, Long>>

    suspend fun totals(
        start: Long,
        end: Long,
    ): Pair<Long, Long>

    /** 按分类分组（只统计指定类型）。 */
    fun observeCategorySums(
        type: TxnType,
        start: Long,
        end: Long,
    ): Flow<List<CategorySum>>

    /** 按天分组（只统计指定类型）。 */
    fun observeDaySums(
        type: TxnType,
        fromKey: Int,
        toKey: Int,
    ): Flow<List<DaySum>>

    suspend fun daySums(
        type: TxnType,
        fromKey: Int,
        toKey: Int,
    ): List<DaySum>

    suspend fun refundedTotal(txnId: String): Long

    /** 关键词 / 类型 / 金额 / 时间检索。 */
    suspend fun search(
        keyword: String,
        type: TxnType?,
        minCents: Long,
        maxCents: Long,
        start: Long,
        end: Long,
    ): List<Txn>

    /** 某账户在区间内的收入 / 支出（分），用于账户详情。 */
    suspend fun accountTotals(
        accountId: String,
        start: Long,
        end: Long,
    ): Pair<Long, Long>

    suspend fun insert(txn: Txn)

    suspend fun update(txn: Txn)

    suspend fun softDelete(
        id: String,
        at: Long,
    )

    suspend fun restore(
        id: String,
        at: Long,
    )

    suspend fun hardDelete(id: String)

    suspend fun setExcluded(
        id: String,
        excluded: Boolean,
        at: Long,
    )

    /** 报销状态写回；[reimbursedAt] 为 null 表示「未报销」（撤销时用）。 */
    suspend fun setReimbursed(
        id: String,
        amount: Long,
        reimbursedAt: Long?,
        at: Long,
        pending: Boolean,
    )

    /** 报销确认生成的收入流水。 */
    suspend fun reimbursementOf(txnId: String): Txn?
}

/** 账户仓库。 */
interface AccountRepository {
    fun observeVisible(): Flow<List<Account>>

    fun observeAll(): Flow<List<Account>>

    /** 含实时余额的账户列表。 */
    fun observeWithBalances(): Flow<List<Account>>

    suspend fun byId(id: String): Account?

    suspend fun balanceOf(id: String): Long

    suspend fun upsert(account: Account)

    suspend fun count(): Int

    /** 回收站里的账户。 */
    fun observeDeleted(): Flow<List<Account>>

    /** 删除账户：账户与其流水一起进回收站（AC-22、AC-34）。 */
    suspend fun deleteWithTxns(
        id: String,
        at: Long,
    )

    /** 从回收站还原账户，连带还原同一次删除的流水。 */
    suspend fun restore(
        id: String,
        at: Long,
    )

    /** 彻底删除账户及其流水。 */
    suspend fun purge(id: String)

    suspend fun setHidden(
        id: String,
        hidden: Boolean,
    )

    suspend fun setSortOrder(
        id: String,
        order: Int,
    )

    /** 该账户的账目笔数。 */
    suspend fun txnCount(id: String): Int

    /**
     * 投资账户累计成本增减（v1.8.0）：转入为正、转出为负，成本下限 0。
     *
     * 只影响 INVEST 账户；非投资账户调用是空操作，避免调用点到处判断类型。
     */
    suspend fun adjustInvestCost(
        id: String,
        deltaCents: Long,
        at: Long,
    )

    /**
     * 修改投资账户市值（v1.8.0）：只改 [Account.marketValueCents]，**不生成流水**（PRD D15）。
     *
     * 同时写一条资产变化事件，供资产报表「资产变化记录」板块展示。
     */
    suspend fun updateMarketValue(
        id: String,
        marketValueCents: Long,
        at: Long,
    )
}

/** 分类仓库。 */
interface CategoryRepository {
    fun observeAll(): Flow<List<Category>>

    suspend fun listAll(): List<Category>

    suspend fun byId(id: String): Category?

    /** 指定类型下的完整分类树（大类 → 小类）。 */
    suspend fun tree(type: TxnType): List<Pair<Category, List<Category>>>

    /** 按使用次数倒序的叶子分类，用于「常用」。 */
    suspend fun frequent(
        type: TxnType,
        limit: Int = 8,
    ): List<Category>

    suspend fun increaseUsage(id: String)

    suspend fun upsert(category: Category)

    // ---------- 分类管理（AC-79） ----------

    /** 该分类下（含子类）未删除的流水条数；>0 时不允许删除。 */
    suspend fun txnCountOf(id: String): Int

    suspend fun setHidden(
        id: String,
        hidden: Boolean,
    )

    /** 物理删除；调用方负责先用 [txnCountOf] 拦住「还有流水」的情况。 */
    suspend fun delete(id: String)
}

/** 标签仓库。 */
interface TagRepository {
    fun observeAll(): Flow<List<Tag>>

    suspend fun listAll(): List<Tag>

    fun observeTagIdsOf(txnId: String): Flow<List<String>>

    suspend fun upsert(tag: Tag): Tag

    /** 按名称查找或新建。 */
    suspend fun findOrCreate(name: String): Tag

    suspend fun setTxnTags(
        txnId: String,
        tagIds: List<String>,
    )

    suspend fun tagIdsOf(txnId: String): List<String>

    /** 删除标签；关联关系一并清掉，流水本身不受影响。 */
    suspend fun delete(id: String)
}

/** 商家仓库。 */
interface MerchantRepository {
    suspend fun suggest(limit: Int = 10): List<Merchant>

    suspend fun findOrCreate(name: String): Merchant

    /** 全部商家，按使用次数倒序（管理页用）。 */
    suspend fun listAll(): List<Merchant>

    suspend fun rename(
        id: String,
        name: String,
    )

    suspend fun delete(id: String)
}

/** 模板仓库。 */
interface TemplateRepository {
    fun observeAll(): Flow<List<Template>>

    suspend fun byId(id: String): Template?

    suspend fun upsert(template: Template)

    suspend fun delete(id: String)
}

/** 预算仓库。 */
interface BudgetRepository {
    /** 该月的全部行（含回收站），用于判断这个月有没有配置过。 */
    fun observeMonthRows(monthKey: Int): Flow<List<Budget>>

    /** 早于 [monthKey] 的最近一个有配置的月份。 */
    fun observeLatestBefore(monthKey: Int): Flow<List<Budget>>

    suspend fun monthRows(monthKey: Int): List<Budget>

    suspend fun latestBefore(monthKey: Int): List<Budget>

    suspend fun byId(
        id: String,
        monthKey: Int,
    ): Budget?

    suspend fun upsert(budget: Budget)

    suspend fun upsertAll(list: List<Budget>)

    /** 软删除（进回收站）。 */
    suspend fun softDelete(
        id: String,
        monthKey: Int,
        at: Long,
    )

    suspend fun restore(
        id: String,
        monthKey: Int,
    )

    /** 回收站里的预算行。 */
    fun observeDeleted(): Flow<List<Budget>>

    suspend fun setEnabled(
        id: String,
        monthKey: Int,
        enabled: Boolean,
    )

    suspend fun delete(
        id: String,
        monthKey: Int,
    )
}

/** 借贷仓库。 */
interface DebtRepository {
    fun observeAll(): Flow<List<Debt>>

    suspend fun all(): List<Debt>

    suspend fun byId(id: String): Debt?

    suspend fun repaidOf(debtId: String): Long

    fun observeRepaidOf(debtId: String): Flow<Long>

    suspend fun upsert(debt: Debt)

    suspend fun delete(id: String)
}

/**
 * 周期记账规则仓库。
 *
 * 执行引擎只依赖 [due]：返回「未暂停 + 已到期 + 未超结束日」的规则，按到期时间正序。
 */
interface RecurringRepository {
    fun observeAll(): Flow<List<RecurringRule>>

    suspend fun due(now: Long): List<RecurringRule>

    suspend fun byId(id: String): RecurringRule?

    suspend fun upsert(rule: RecurringRule)

    suspend fun setPaused(
        id: String,
        paused: Boolean,
        nextRunAt: Long,
    )

    /** 执行后推进游标：写回下次执行时间与已执行次数。 */
    suspend fun advance(
        id: String,
        nextRunAt: Long,
        runTimes: Int,
        paused: Boolean,
    )

    suspend fun delete(id: String)
}
