package com.xxjz.ledger.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import com.xxjz.ledger.data.local.entity.BudgetEntity
import com.xxjz.ledger.data.local.entity.CategoryEntity
import com.xxjz.ledger.data.local.entity.DebtEntity
import com.xxjz.ledger.data.local.entity.MerchantEntity
import com.xxjz.ledger.data.local.entity.RecurringRuleEntity
import com.xxjz.ledger.data.local.entity.TagEntity
import com.xxjz.ledger.data.local.entity.TemplateEntity
import com.xxjz.ledger.data.local.entity.TxnTagEntity
import kotlinx.coroutines.flow.Flow

/** 分类 DAO。 */
@Dao
interface CategoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(list: List<CategoryEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(category: CategoryEntity)

    @Update
    suspend fun update(category: CategoryEntity)

    @Upsert
    suspend fun upsert(category: CategoryEntity)

    @Query("SELECT * FROM category WHERE id = :id")
    suspend fun byId(id: String): CategoryEntity?

    @Query("SELECT * FROM category ORDER BY type ASC, sortOrder ASC, name ASC")
    fun observeAll(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM category ORDER BY type ASC, sortOrder ASC, name ASC")
    suspend fun listAll(): List<CategoryEntity>

    @Query("SELECT * FROM category WHERE type = :type AND hidden = 0 ORDER BY sortOrder ASC, name ASC")
    suspend fun listByType(type: String): List<CategoryEntity>

    @Query("SELECT * FROM category WHERE parentId = :parentId AND hidden = 0 ORDER BY sortOrder ASC, name ASC")
    suspend fun childrenOf(parentId: String): List<CategoryEntity>

    @Query("SELECT COUNT(*) FROM category")
    suspend fun count(): Int

    @Query("UPDATE category SET usageCount = usageCount + 1 WHERE id = :id")
    suspend fun increaseUsage(id: String)

    @Query("UPDATE category SET hidden = :hidden WHERE id = :id")
    suspend fun setHidden(
        id: String,
        hidden: Boolean,
    )

    @Query("DELETE FROM category WHERE id = :id")
    suspend fun delete(id: String)
}

/** 标签 DAO。 */
@Dao
interface TagDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(tag: TagEntity)

    @Upsert
    suspend fun upsert(tag: TagEntity)

    @Query("SELECT * FROM tag ORDER BY usageCount DESC, name ASC")
    fun observeAll(): Flow<List<TagEntity>>

    @Query("SELECT * FROM tag ORDER BY usageCount DESC, name ASC")
    suspend fun listAll(): List<TagEntity>

    @Query("SELECT * FROM txn_tag")
    suspend fun listAllLinks(): List<TxnTagEntity>

    @Query("UPDATE tag SET usageCount = usageCount + 1 WHERE id = :id")
    suspend fun increaseUsage(id: String)

    @Query("DELETE FROM tag WHERE id = :id")
    suspend fun delete(id: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun linkTags(links: List<TxnTagEntity>)

    @Query("SELECT tagId FROM txn_tag WHERE txnId = :txnId")
    suspend fun tagIdsOf(txnId: String): List<String>

    @Query("SELECT tagId FROM txn_tag WHERE txnId = :txnId")
    fun observeTagIdsOf(txnId: String): Flow<List<String>>

    @Query("DELETE FROM txn_tag WHERE txnId = :txnId")
    suspend fun clearTags(txnId: String)

    @Query("SELECT * FROM tag WHERE id IN (:ids)")
    suspend fun tagsByIds(ids: List<String>): List<TagEntity>
}

/** 商家 DAO（仅增删改，不做合并）。 */
@Dao
interface MerchantDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(merchant: MerchantEntity)

    @Query("SELECT * FROM merchant ORDER BY usageCount DESC, name ASC LIMIT :limit")
    suspend fun suggest(limit: Int = 10): List<MerchantEntity>

    @Query("SELECT * FROM merchant ORDER BY usageCount DESC, name ASC")
    suspend fun listAll(): List<MerchantEntity>

    @Query("SELECT * FROM merchant WHERE name = :name LIMIT 1")
    suspend fun byName(name: String): MerchantEntity?

    @Query("UPDATE merchant SET usageCount = usageCount + 1 WHERE id = :id")
    suspend fun increaseUsage(id: String)

    @Query("DELETE FROM merchant WHERE id = :id")
    suspend fun delete(id: String)
}

/** 模板 DAO。 */
@Dao
interface TemplateDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(template: TemplateEntity)

    @Upsert
    suspend fun upsert(template: TemplateEntity)

    @Query("SELECT * FROM template ORDER BY sortOrder ASC, name ASC")
    fun observeAll(): Flow<List<TemplateEntity>>

    @Query("SELECT * FROM template ORDER BY sortOrder ASC, name ASC")
    suspend fun listAll(): List<TemplateEntity>

    @Query("SELECT * FROM template WHERE id = :id")
    suspend fun byId(id: String): TemplateEntity?

    @Query("UPDATE template SET sortOrder = :order WHERE id = :id")
    suspend fun setSortOrder(
        id: String,
        order: Int,
    )

    @Delete
    suspend fun delete(template: TemplateEntity)

    @Query("DELETE FROM template WHERE id = :id")
    suspend fun deleteById(id: String)
}

/** 预算 DAO。 */
@Dao
interface BudgetDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(budget: BudgetEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(list: List<BudgetEntity>)

    @Upsert
    suspend fun upsert(budget: BudgetEntity)

    /** 该月的全部行（含回收站里的），用于判断「这个月有没有配置过」。 */
    @Query("SELECT * FROM budget WHERE monthKey = :monthKey ORDER BY sortOrder, id")
    fun observeMonthRows(monthKey: Int): Flow<List<BudgetEntity>>

    @Query("SELECT * FROM budget WHERE monthKey = :monthKey ORDER BY sortOrder, id")
    suspend fun monthRows(monthKey: Int): List<BudgetEntity>

    /** 早于 [monthKey] 的最近一个有配置的月份的全部行。 */
    @Query(
        """
        SELECT * FROM budget
        WHERE monthKey = (SELECT MAX(monthKey) FROM budget WHERE monthKey < :monthKey)
        ORDER BY sortOrder, id
        """,
    )
    fun observeLatestBefore(monthKey: Int): Flow<List<BudgetEntity>>

    @Query(
        """
        SELECT * FROM budget
        WHERE monthKey = (SELECT MAX(monthKey) FROM budget WHERE monthKey < :monthKey)
        ORDER BY sortOrder, id
        """,
    )
    suspend fun latestBefore(monthKey: Int): List<BudgetEntity>

    @Query("SELECT * FROM budget WHERE id = :id AND monthKey = :monthKey")
    suspend fun byId(
        id: String,
        monthKey: Int,
    ): BudgetEntity?

    /** 回收站里的预算。 */
    @Query("SELECT * FROM budget WHERE deletedAt IS NOT NULL ORDER BY deletedAt DESC")
    fun observeDeleted(): Flow<List<BudgetEntity>>

    /** 备份用：含回收站里的预算行。 */
    @Query("SELECT * FROM budget ORDER BY monthKey ASC, sortOrder ASC, id ASC")
    suspend fun listAllIncludingDeleted(): List<BudgetEntity>

    @Query("UPDATE budget SET deletedAt = :at WHERE id = :id AND monthKey = :monthKey")
    suspend fun softDelete(
        id: String,
        monthKey: Int,
        at: Long,
    )

    @Query("UPDATE budget SET deletedAt = NULL WHERE id = :id AND monthKey = :monthKey")
    suspend fun restore(
        id: String,
        monthKey: Int,
    )

    @Query("UPDATE budget SET enabled = :enabled WHERE id = :id AND monthKey = :monthKey")
    suspend fun setEnabled(
        id: String,
        monthKey: Int,
        enabled: Boolean,
    )

    @Query("DELETE FROM budget WHERE id = :id AND monthKey = :monthKey")
    suspend fun delete(
        id: String,
        monthKey: Int,
    )
}

/** 周期规则 DAO。 */
@Dao
interface RecurringDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(rule: RecurringRuleEntity)

    @Upsert
    suspend fun upsert(rule: RecurringRuleEntity)

    /**
     * 到期规则：未暂停、游标已到、且游标没越过结束日。
     *
     * 注意不要用 `endAt >= now` 过滤：结束日在过去但仍有未执行的时刻时（用户两个月没开 App），
     * 这些时刻必须补出来，否则就是漏账。执行完引擎会把游标推到 endAt 之后并置 paused，自然不会再扫到。
     */
    @Query(
        """
        SELECT * FROM recurring_rule
        WHERE paused = 0 AND nextRunAt <= :now AND (endAt IS NULL OR nextRunAt <= endAt)
        ORDER BY nextRunAt ASC
        """,
    )
    suspend fun dueRules(now: Long): List<RecurringRuleEntity>

    @Query("SELECT * FROM recurring_rule ORDER BY nextRunAt ASC")
    fun observeAll(): Flow<List<RecurringRuleEntity>>

    @Query("SELECT * FROM recurring_rule ORDER BY nextRunAt ASC")
    suspend fun listAll(): List<RecurringRuleEntity>

    @Query("SELECT * FROM recurring_rule WHERE id = :id")
    suspend fun byId(id: String): RecurringRuleEntity?

    @Query("UPDATE recurring_rule SET paused = :paused, nextRunAt = :nextRunAt WHERE id = :id")
    suspend fun setPaused(
        id: String,
        paused: Boolean,
        nextRunAt: Long,
    )

    @Query("UPDATE recurring_rule SET nextRunAt = :nextRunAt, runTimes = :runTimes, paused = :paused WHERE id = :id")
    suspend fun advance(
        id: String,
        nextRunAt: Long,
        runTimes: Int,
        paused: Boolean,
    )

    @Query("DELETE FROM recurring_rule WHERE id = :id")
    suspend fun delete(id: String)
}

/** 借贷 DAO。剩余额 = 本金 - SUM(还款流水)。 */
@Dao
interface DebtDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(debt: DebtEntity)

    @Upsert
    suspend fun upsert(debt: DebtEntity)

    @Query("SELECT * FROM debt WHERE id = :id")
    suspend fun byId(id: String): DebtEntity?

    @Query("SELECT * FROM debt ORDER BY settled ASC, startAt DESC")
    fun observeAll(): Flow<List<DebtEntity>>

    @Query("SELECT * FROM debt ORDER BY settled ASC, startAt DESC")
    suspend fun listAll(): List<DebtEntity>

    @Query(
        """
        SELECT d.id AS `key`,
               d.principal - COALESCE((
                   SELECT SUM(t.amount) FROM txn t
                   WHERE t.deletedAt IS NULL AND t.debtId = d.id
               ), 0) AS total,
               0 AS cnt
        FROM debt d
        """,
    )
    fun observeRemaining(): Flow<List<MoneyBucket>>

    @Query("SELECT COALESCE(SUM(amount), 0) FROM txn WHERE debtId = :debtId AND deletedAt IS NULL")
    suspend fun repaidTotal(debtId: String): Long

    @Query("SELECT COALESCE(SUM(amount), 0) FROM txn WHERE debtId = :debtId AND deletedAt IS NULL")
    fun observeRepaidTotal(debtId: String): Flow<Long>

    @Query("DELETE FROM debt WHERE id = :id")
    suspend fun delete(id: String)

    /**
     * 清空台账（v1.9.0 迁移收尾）。
     *
     * 数据已经搬成借贷账户，留着只会让备份越背越重、恢复时新旧两份并存。
     * 表结构不 DROP：真要回滚到 v1.8.x，表的形状还在，能接回旧版本读写。
     */
    @Query("DELETE FROM debt")
    suspend fun deleteAll()
}
