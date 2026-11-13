package com.xxjz.ledger.data.local.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import com.xxjz.ledger.data.local.entity.AccountEntity
import kotlinx.coroutines.flow.Flow

/** 账户余额聚合行。 */
data class AccountBalanceRow(
    @ColumnInfo(name = "accountId") val accountId: String?,
    @ColumnInfo(name = "balance") val balance: Long,
)

/**
 * 账户 DAO。
 *
 * 余额公式（DEV_PLAN M3 关键技术点）：
 * `初始余额 + Σ收入 - Σ支出 + Σ转入 - Σ转出 - Σ手续费`
 * 一条 SQL 算完，不在 Kotlin 侧循环累加。
 */
@Dao
interface AccountDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(accounts: List<AccountEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(account: AccountEntity)

    @Update
    suspend fun update(account: AccountEntity)

    @Upsert
    suspend fun upsert(account: AccountEntity)

    @Query("SELECT * FROM account WHERE id = :id")
    suspend fun byId(id: String): AccountEntity?

    @Query("SELECT * FROM account WHERE id = :id")
    fun observeById(id: String): Flow<AccountEntity?>

    @Query("SELECT * FROM account WHERE hidden = 0 AND deletedAt IS NULL ORDER BY sortOrder ASC, name ASC")
    fun observeVisible(): Flow<List<AccountEntity>>

    /** 全部未删除账户（含隐藏）；账户页与余额计算用。 */
    @Query("SELECT * FROM account WHERE deletedAt IS NULL ORDER BY sortOrder ASC, name ASC")
    fun observeAll(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM account ORDER BY sortOrder ASC, name ASC")
    suspend fun listAll(): List<AccountEntity>

    /** 备份用：包含已进回收站的账户，保证「导回去和导出来一模一样」。 */
    @Query("SELECT * FROM account ORDER BY sortOrder ASC, name ASC")
    suspend fun listAllIncludingDeleted(): List<AccountEntity>

    @Query("SELECT * FROM account WHERE deletedAt IS NOT NULL ORDER BY deletedAt DESC")
    fun observeDeleted(): Flow<List<AccountEntity>>

    @Query("SELECT COUNT(*) FROM account WHERE deletedAt IS NULL")
    suspend fun count(): Int

    @Query("DELETE FROM account WHERE id = :id")
    suspend fun delete(id: String)

    /** 回收站：只打时间戳，流水另行软删（见 `deleteWithTxns`）。 */
    @Query("UPDATE account SET deletedAt = :at WHERE id = :id")
    suspend fun softDelete(
        id: String,
        at: Long,
    )

    @Query("UPDATE account SET deletedAt = NULL WHERE id = :id")
    suspend fun restore(id: String)

    @Query("SELECT deletedAt FROM account WHERE id = :id")
    suspend fun deletedAtOf(id: String): Long?

    @Query("UPDATE account SET hidden = :hidden WHERE id = :id")
    suspend fun setHidden(
        id: String,
        hidden: Boolean,
    )

    @Query("UPDATE account SET sortOrder = :order WHERE id = :id")
    suspend fun setSortOrder(
        id: String,
        order: Int,
    )

    /**
     * 全部账户的实时余额。
     * 转账只影响 `accountId`（转出）与 `toAccountId`（转入），手续费从转出账户扣。
     */
    @Query(
        """
        SELECT a.id AS accountId,
               a.initialBalance
               + COALESCE((
                   SELECT SUM(t.amount) FROM txn t
                   WHERE t.deletedAt IS NULL AND t.accountId = a.id AND t.type = 'INCOME'
               ), 0)
               - COALESCE((
                   SELECT SUM(t.amount) FROM txn t
                   WHERE t.deletedAt IS NULL AND t.accountId = a.id AND t.type = 'EXPENSE'
               ), 0)
               - COALESCE((
                   SELECT SUM(t.amount + t.fee) FROM txn t
                   WHERE t.deletedAt IS NULL AND t.accountId = a.id AND t.type = 'TRANSFER'
               ), 0)
               + COALESCE((
                   SELECT SUM(t.amount) FROM txn t
                   WHERE t.deletedAt IS NULL AND t.toAccountId = a.id AND t.type = 'TRANSFER'
               ), 0) AS balance
        FROM account a
        """,
    )
    fun observeBalances(): Flow<List<AccountBalanceRow>>

    @Query(
        """
        SELECT a.id AS accountId,
               a.initialBalance
               + COALESCE((
                   SELECT SUM(t.amount) FROM txn t
                   WHERE t.deletedAt IS NULL AND t.accountId = a.id AND t.type = 'INCOME'
               ), 0)
               - COALESCE((
                   SELECT SUM(t.amount) FROM txn t
                   WHERE t.deletedAt IS NULL AND t.accountId = a.id AND t.type = 'EXPENSE'
               ), 0)
               - COALESCE((
                   SELECT SUM(t.amount + t.fee) FROM txn t
                   WHERE t.deletedAt IS NULL AND t.accountId = a.id AND t.type = 'TRANSFER'
               ), 0)
               + COALESCE((
                   SELECT SUM(t.amount) FROM txn t
                   WHERE t.deletedAt IS NULL AND t.toAccountId = a.id AND t.type = 'TRANSFER'
               ), 0) AS balance
        FROM account a WHERE a.id = :id
        """,
    )
    suspend fun balanceOf(id: String): AccountBalanceRow?
}
