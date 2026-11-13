package com.xxjz.ledger.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.xxjz.ledger.data.local.entity.AccountEntity
import com.xxjz.ledger.data.local.entity.BudgetEntity
import com.xxjz.ledger.data.local.entity.CategoryEntity
import com.xxjz.ledger.data.local.entity.DebtEntity
import com.xxjz.ledger.data.local.entity.LedgerEntity
import com.xxjz.ledger.data.local.entity.MerchantEntity
import com.xxjz.ledger.data.local.entity.RecurringRuleEntity
import com.xxjz.ledger.data.local.entity.TagEntity
import com.xxjz.ledger.data.local.entity.TemplateEntity
import com.xxjz.ledger.data.local.entity.TxnEntity
import com.xxjz.ledger.data.local.entity.TxnTagEntity

/**
 * 备份/还原专用 DAO：整表清空 + 批量写入，以及回收站定时清理。
 *
 * 之所以单独放一个 DAO：还原要「全表覆盖」，语义上不属于任何业务 DAO；
 * 把这些危险语句集中在一处，也便于审计。
 */
@Dao
interface BackupDao {
    @Query("DELETE FROM txn_tag")
    suspend fun clearTxnTags()

    @Query("DELETE FROM txn")
    suspend fun clearTxns()

    @Query("DELETE FROM account")
    suspend fun clearAccounts()

    @Query("DELETE FROM category")
    suspend fun clearCategories()

    @Query("DELETE FROM tag")
    suspend fun clearTags()

    @Query("DELETE FROM merchant")
    suspend fun clearMerchants()

    @Query("DELETE FROM template")
    suspend fun clearTemplates()

    @Query("DELETE FROM budget")
    suspend fun clearBudgets()

    @Query("DELETE FROM recurring_rule")
    suspend fun clearRecurringRules()

    @Query("DELETE FROM debt")
    suspend fun clearDebts()

    @Query("DELETE FROM ledger")
    suspend fun clearLedgers()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTxns(list: List<TxnEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTxnTags(list: List<TxnTagEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAccounts(list: List<AccountEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCategories(list: List<CategoryEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTags(list: List<TagEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMerchants(list: List<MerchantEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTemplates(list: List<TemplateEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBudgets(list: List<BudgetEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecurringRules(list: List<RecurringRuleEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDebts(list: List<DebtEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLedgers(list: List<LedgerEntity>)

    // ---------- 回收站定时清理（AC-76） ----------

    /** 清掉软删时间早于 [cutoff] 的账目。 */
    @Query("DELETE FROM txn WHERE deletedAt IS NOT NULL AND deletedAt < :cutoff")
    suspend fun purgeTxnsBefore(cutoff: Long): Int

    @Query("DELETE FROM txn_tag WHERE txnId NOT IN (SELECT id FROM txn)")
    suspend fun purgeOrphanTxnTags(): Int

    @Query("DELETE FROM account WHERE deletedAt IS NOT NULL AND deletedAt < :cutoff")
    suspend fun purgeAccountsBefore(cutoff: Long): Int

    @Query("DELETE FROM budget WHERE deletedAt IS NOT NULL AND deletedAt < :cutoff")
    suspend fun purgeBudgetsBefore(cutoff: Long): Int
}
