package com.xxjz.ledger.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import com.xxjz.ledger.data.local.entity.TxnEntity
import kotlinx.coroutines.flow.Flow

/**
 * 账目 DAO。
 *
 * 约定：
 * - 所有读查询默认排除软删除（`deletedAt IS NULL`）。
 * - 统计类查询排除「不计入收支」与「转账/借贷」类型（AC-12）。
 * - 退款收入（`refundOfTxnId IS NOT NULL`）计入收入，但同时会从原支出的净额中扣回，见 [refundedTotal]。
 */
@Dao
interface TxnDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(txn: TxnEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(txns: List<TxnEntity>)

    @Update
    suspend fun update(txn: TxnEntity)

    @Upsert
    suspend fun upsert(txn: TxnEntity)

    @Query("SELECT * FROM txn WHERE id = :id")
    suspend fun byId(id: String): TxnEntity?

    @Query("SELECT * FROM txn WHERE id = :id")
    fun observeById(id: String): Flow<TxnEntity?>

    /** 按月份区间取流水，倒序（时间相同则按创建时间）。 */
    @Query(
        """
        SELECT * FROM txn
        WHERE deletedAt IS NULL AND dateTime BETWEEN :start AND :end
        ORDER BY dateTime DESC, createdAt DESC
        """,
    )
    fun observeInRange(
        start: Long,
        end: Long,
    ): Flow<List<TxnEntity>>

    @Query(
        """
        SELECT * FROM txn
        WHERE deletedAt IS NULL AND dateTime BETWEEN :start AND :end
        ORDER BY dateTime DESC, createdAt DESC
        """,
    )
    suspend fun listInRange(
        start: Long,
        end: Long,
    ): List<TxnEntity>

    /** 按账户取流水。 */
    @Query(
        """
        SELECT * FROM txn
        WHERE deletedAt IS NULL AND (accountId = :accountId OR toAccountId = :accountId)
        ORDER BY dateTime DESC, createdAt DESC
        """,
    )
    fun observeByAccount(accountId: String): Flow<List<TxnEntity>>

    /** 待报销列表。 */
    @Query(
        """
        SELECT * FROM txn
        WHERE deletedAt IS NULL AND pendingReimbursement = 1 AND type = 'EXPENSE'
        ORDER BY dateTime DESC, createdAt DESC
        """,
    )
    fun observePendingReimbursement(): Flow<List<TxnEntity>>

    @Query(
        """
        SELECT * FROM txn
        WHERE deletedAt IS NULL AND pendingReimbursement = 1 AND type = 'EXPENSE'
        ORDER BY dateTime DESC, createdAt DESC
        """,
    )
    suspend fun listPendingReimbursement(): List<TxnEntity>

    /** 备份与资产报表用：含回收站里的账目，按时间升序（净资产回放需要删除时刻）。 */
    @Query("SELECT * FROM txn ORDER BY dateTime ASC, createdAt ASC")
    suspend fun listAllIncludingDeleted(): List<TxnEntity>

    /** 回收站。 */
    @Query("SELECT * FROM txn WHERE deletedAt IS NOT NULL ORDER BY deletedAt DESC")
    fun observeDeleted(): Flow<List<TxnEntity>>

    @Query("SELECT COUNT(*) FROM txn WHERE deletedAt IS NOT NULL")
    fun observeDeletedCount(): Flow<Int>

    /**
     * 资产报表用：**含回收站**的全部流水，按时间升序。
     *
     * 为什么要给报表一个单独的查询：净资产回放需要「软删除时刻」把该笔影响反向撤销，
     * 如果沿用只返回未删除流水的方法，删除这件事就从曲线里消失了。
     */
    @Query("SELECT * FROM txn ORDER BY dateTime ASC, createdAt ASC")
    fun observeAllForReport(): Flow<List<TxnEntity>>

    // ---------- 统计 ----------

    /**
     * 区间内按类型汇总：返回 `type -> (sum, cnt)`。
     * 排除软删除与不计入收支的记录。
     */
    @Query(
        """
        SELECT type AS `key`, SUM(amount) AS total, COUNT(*) AS cnt FROM txn
        WHERE deletedAt IS NULL AND excludedFromStats = 0 AND type IN ('EXPENSE', 'INCOME')
          AND dateTime BETWEEN :start AND :end
        GROUP BY type
        """,
    )
    fun observeSumByType(
        start: Long,
        end: Long,
    ): Flow<List<MoneyBucket>>

    @Query(
        """
        SELECT type AS `key`, SUM(amount) AS total, COUNT(*) AS cnt FROM txn
        WHERE deletedAt IS NULL AND excludedFromStats = 0 AND type IN ('EXPENSE', 'INCOME')
          AND dateTime BETWEEN :start AND :end
        GROUP BY type
        """,
    )
    suspend fun sumByType(
        start: Long,
        end: Long,
    ): List<MoneyBucket>

    /** 区间内按分类分组（只统计收入或支出，由 `type` 指定）。 */
    @Query(
        """
        SELECT categoryId AS `key`, SUM(amount) AS total, COUNT(*) AS cnt FROM txn
        WHERE deletedAt IS NULL AND excludedFromStats = 0 AND type = :type
          AND dateTime BETWEEN :start AND :end
        GROUP BY categoryId
        ORDER BY total DESC
        """,
    )
    fun observeSumByCategory(
        type: String,
        start: Long,
        end: Long,
    ): Flow<List<MoneyBucket>>

    @Query(
        """
        SELECT categoryId AS `key`, SUM(amount) AS total, COUNT(*) AS cnt FROM txn
        WHERE deletedAt IS NULL AND excludedFromStats = 0 AND type = :type
          AND dateTime BETWEEN :start AND :end
        GROUP BY categoryId
        ORDER BY total DESC
        """,
    )
    suspend fun sumByCategory(
        type: String,
        start: Long,
        end: Long,
    ): List<MoneyBucket>

    /** 按天分组，用于日历圆点与明细分组头。key 为 `dateKey` 字符串。 */
    @Query(
        """
        SELECT CAST(dateKey AS TEXT) AS `key`, SUM(amount) AS total, COUNT(*) AS cnt FROM txn
        WHERE deletedAt IS NULL AND excludedFromStats = 0 AND type = :type
          AND dateKey BETWEEN :fromKey AND :toKey
        GROUP BY dateKey
        ORDER BY dateKey ASC
        """,
    )
    fun observeSumByDay(
        type: String,
        fromKey: Int,
        toKey: Int,
    ): Flow<List<MoneyBucket>>

    @Query(
        """
        SELECT CAST(dateKey AS TEXT) AS `key`, SUM(amount) AS total, COUNT(*) AS cnt FROM txn
        WHERE deletedAt IS NULL AND excludedFromStats = 0 AND type = :type
          AND dateKey BETWEEN :fromKey AND :toKey
        GROUP BY dateKey
        ORDER BY dateKey ASC
        """,
    )
    suspend fun sumByDay(
        type: String,
        fromKey: Int,
        toKey: Int,
    ): List<MoneyBucket>

    /** 某账户在区间内的收入 / 支出（用于账户详情）。 */
    @Query(
        """
        SELECT type AS `key`, SUM(amount) AS total, COUNT(*) AS cnt FROM txn
        WHERE deletedAt IS NULL AND excludedFromStats = 0 AND accountId = :accountId
          AND dateTime BETWEEN :start AND :end
        GROUP BY type
        """,
    )
    suspend fun sumByAccount(
        accountId: String,
        start: Long,
        end: Long,
    ): List<MoneyBucket>

    /** 某笔支出已退款总额（AC-11 的校验依据）。 */
    @Query("SELECT COALESCE(SUM(amount), 0) FROM txn WHERE refundOfTxnId = :txnId AND deletedAt IS NULL")
    suspend fun refundedTotal(txnId: String): Long

    @Query("SELECT COALESCE(SUM(amount), 0) FROM txn WHERE refundOfTxnId = :txnId AND deletedAt IS NULL")
    fun observeRefundedTotal(txnId: String): Flow<Long>

    /** 某借贷记录已还 / 已收总额。 */
    @Query("SELECT COALESCE(SUM(amount), 0) FROM txn WHERE debtId = :debtId AND deletedAt IS NULL")
    suspend fun repaidTotal(debtId: String): Long

    /** 分类使用次数（AC-05「常用」排序依据）。 */
    @Query("SELECT COUNT(*) FROM txn WHERE categoryId = :categoryId AND deletedAt IS NULL")
    suspend fun usageCountOf(categoryId: String): Int

    /** 按分类统计使用次数，返回 `categoryId -> cnt`。 */
    @Query(
        """
        SELECT categoryId AS `key`, COUNT(*) AS cnt, 0 AS total FROM txn
        WHERE deletedAt IS NULL AND categoryId IS NOT NULL
        GROUP BY categoryId
        """,
    )
    fun observeCategoryUsage(): Flow<List<MoneyBucket>>

    // ---------- 写操作 ----------

    @Query("UPDATE txn SET deletedAt = :at, updatedAt = :at WHERE id = :id")
    suspend fun softDelete(
        id: String,
        at: Long,
    )

    @Query("UPDATE txn SET deletedAt = NULL, updatedAt = :at WHERE id = :id")
    suspend fun restore(
        id: String,
        at: Long,
    )

    @Query("DELETE FROM txn WHERE id = :id")
    suspend fun hardDelete(id: String)

    @Query("DELETE FROM txn WHERE accountId = :accountId OR toAccountId = :accountId")
    suspend fun hardDeleteByAccount(accountId: String)

    /** 删除账户时级联软删除该账户的收支（含转入转出）。 */
    @Query(
        """
        UPDATE txn SET deletedAt = :at, updatedAt = :at
        WHERE deletedAt IS NULL AND (accountId = :accountId OR toAccountId = :accountId)
        """,
    )
    suspend fun softDeleteByAccount(
        accountId: String,
        at: Long,
    )

    /** 回收站还原账户时，把同一次删除连带软删的流水一并捞回来。 */
    @Query(
        """
        UPDATE txn SET deletedAt = NULL, updatedAt = :at
        WHERE deletedAt = :deletedAt AND (accountId = :accountId OR toAccountId = :accountId)
        """,
    )
    suspend fun restoreByAccount(
        accountId: String,
        deletedAt: Long,
        at: Long,
    )

    @Query(
        """
        SELECT COUNT(*) FROM txn
        WHERE deletedAt IS NULL AND (accountId = :accountId OR toAccountId = :accountId)
        """,
    )
    suspend fun countByAccount(accountId: String): Int

    @Query("UPDATE txn SET excludedFromStats = :excluded, updatedAt = :at WHERE id = :id")
    suspend fun setExcluded(
        id: String,
        excluded: Boolean,
        at: Long,
    )

    @Query(
        """
        UPDATE txn SET reimbursedAmount = :amount, reimbursedAt = :reimbursedAt,
                       pendingReimbursement = :pending, updatedAt = :at
        WHERE id = :id
        """,
    )
    suspend fun setReimbursed(
        id: String,
        amount: Long,
        reimbursedAt: Long?,
        at: Long,
        pending: Boolean,
    )

    /** 该账目对应的报销收入流水（报销确认时生成，`parentTxnId` 指回原支出）。 */
    @Query("SELECT * FROM txn WHERE parentTxnId = :txnId AND type = 'INCOME' AND deletedAt IS NULL LIMIT 1")
    suspend fun reimbursementOf(txnId: String): TxnEntity?

    @Query("SELECT COUNT(*) FROM txn WHERE deletedAt IS NULL")
    fun observeTotalCount(): Flow<Int>

    @Query("SELECT * FROM txn WHERE deletedAt IS NULL ORDER BY dateTime DESC, createdAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<TxnEntity>>

    /** 关键词 + 类型 + 金额区间 + 时间区间检索（AC-28 / AC-30）。 */
    @Query(
        """
        SELECT * FROM txn
        WHERE deletedAt IS NULL
          AND (:keyword = '' OR note LIKE '%' || :keyword || '%' OR merchantName LIKE '%' || :keyword || '%')
          AND (:type = '' OR type = :type)
          AND amount BETWEEN :minAmount AND :maxAmount
          AND dateTime BETWEEN :start AND :end
        ORDER BY dateTime DESC, createdAt DESC
        """,
    )
    suspend fun search(
        keyword: String,
        type: String,
        minAmount: Long,
        maxAmount: Long,
        start: Long,
        end: Long,
    ): List<TxnEntity>
}
