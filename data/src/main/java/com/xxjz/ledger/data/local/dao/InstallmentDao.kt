package com.xxjz.ledger.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.xxjz.ledger.data.local.entity.InstallmentEntity
import kotlinx.coroutines.flow.Flow

/** 分期计划访问（v1.9.0 / PRD 3.2）。 */
@Dao
interface InstallmentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(plan: InstallmentEntity)

    @Upsert
    suspend fun upsert(plan: InstallmentEntity)

    @Query("SELECT * FROM installment WHERE id = :id")
    suspend fun byId(id: String): InstallmentEntity?

    /** 某个信用账户的全部分期（含已结清的，界面自己按进度过滤）。 */
    @Query("SELECT * FROM installment WHERE accountId = :accountId ORDER BY createdAt DESC")
    fun observeByAccount(accountId: String): Flow<List<InstallmentEntity>>

    @Query("SELECT * FROM installment WHERE accountId = :accountId ORDER BY createdAt DESC")
    suspend fun listByAccount(accountId: String): List<InstallmentEntity>

    /** 整表响应式：资产报表与账户页需要跨账户统计。 */
    @Query("SELECT * FROM installment ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<InstallmentEntity>>

    @Query("SELECT COUNT(*) FROM installment WHERE txnId = :txnId")
    suspend fun countByTxn(txnId: String): Int

    @Query("DELETE FROM installment WHERE id = :id")
    suspend fun delete(id: String)
}
