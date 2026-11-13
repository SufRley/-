package com.xxjz.ledger.data.local.seed

import androidx.room.withTransaction
import com.xxjz.ledger.data.local.db.AppDatabase

/**
 * 预置数据写入器。幂等：靠 `app_meta` 里的 `seed_version` 判断，重复调用不会重复插入。
 */
object Seeder {
    suspend fun seedIfNeeded(db: AppDatabase): Boolean {
        val current = db.metaDao().get(AppDatabase.KEY_SEED)?.toIntOrNull() ?: 0
        if (current >= AppDatabase.SEED_VERSION) return false
        db.withTransaction {
            if (db.categoryDao().count() == 0) {
                db.categoryDao().insertAll(SeedData.categories())
            }
            if (db.accountDao().count() == 0) {
                db.accountDao().insertAll(SeedData.defaultAccounts())
            }
            if (db.ledgerDao().defaultLedger() == null) {
                db.ledgerDao().upsert(SeedData.defaultLedger())
            }
            db.metaDao().put(
                com.xxjz.ledger.data.local.dao.MetaEntity(
                    key = AppDatabase.KEY_SEED,
                    value = AppDatabase.SEED_VERSION.toString(),
                ),
            )
        }
        return true
    }
}
