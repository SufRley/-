package com.xxjz.ledger.data.local.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 迁移集合。
 *
 * v1 → v2（M6 预算）：`budget` 表从「月 + 范围 + 单分类」扩展为「名称 / 周期 / 多分类 / 启用 / 软删除」。
 * v1 没有预算界面，表实际为空；这里仍按字段映射搬运旧行，避免任何假定的数据丢失。
 */
val MIGRATION_1_2: Migration =
    object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `budget_new` (
                    `id` TEXT NOT NULL,
                    `monthKey` INTEGER NOT NULL,
                    `name` TEXT NOT NULL,
                    `period` TEXT NOT NULL,
                    `scope` TEXT NOT NULL,
                    `categoryIds` TEXT NOT NULL,
                    `amount` INTEGER NOT NULL,
                    `enabled` INTEGER NOT NULL,
                    `deletedAt` INTEGER,
                    `sortOrder` INTEGER NOT NULL,
                    PRIMARY KEY(`id`, `monthKey`)
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO `budget_new` (`id`, `monthKey`, `name`, `period`, `scope`, `categoryIds`, `amount`, `enabled`, `deletedAt`, `sortOrder`)
                SELECT `id`, `monthKey`, '预算', 'MONTH', `scope`, COALESCE(`categoryId`, ''), `amount`, 1, NULL, 0 FROM `budget`
                """.trimIndent(),
            )
            db.execSQL("DROP TABLE `budget`")
            db.execSQL("ALTER TABLE `budget_new` RENAME TO `budget`")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_budget_monthKey` ON `budget` (`monthKey`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_budget_deletedAt` ON `budget` (`deletedAt`)")
        }
    }

/**
 * v2 → v3（M7 借贷）：`debt` 增加 `writtenOff`，用于「坏账」标记（AC-65）。
 * 默认 0，历史数据全部视为正常未结清。
 */
val MIGRATION_2_3: Migration =
    object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `debt` ADD COLUMN `writtenOff` INTEGER NOT NULL DEFAULT 0")
        }
    }

/**
 * v3 → v4（M8 回收站）：`account` 增加 `deletedAt`。
 *
 * 之前删除账户是物理删除（连带软删流水），进不了回收站；改成软删后需要这一列。
 * 历史账户一律视为未删除。
 */
val MIGRATION_3_4: Migration =
    object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `account` ADD COLUMN `deletedAt` INTEGER")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_account_deletedAt` ON `account` (`deletedAt`)")
        }
    }

/**
 * v4 → v5（v1.8.0 账户五分类）：`account` 增加投资三字段，并把老 `FUND` 全部转成 `SAVINGS`。
 *
 * - `costCents` / `marketValueCents` 默认 0，`includeInNetAssets` 默认 1（计入净资产）；
 * - 老版本只有「资金 / 信用 / 借贷」三类，按 PRD D4，资金账户一律落到储蓄卡，现金由用户自己改回；
 * - 软删（在回收站）的账户在同一张表里，上面的 UPDATE 天然覆盖，恢复后类型同样正确；
 * - 老数据没有投资账户，市值默认 0 不会影响净资产口径。
 */
val MIGRATION_4_5: Migration =
    object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `account` ADD COLUMN `costCents` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `account` ADD COLUMN `marketValueCents` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `account` ADD COLUMN `includeInNetAssets` INTEGER NOT NULL DEFAULT 1")
            db.execSQL("UPDATE `account` SET `type` = 'SAVINGS' WHERE `type` = 'FUND'")
        }
    }

/**
 * v5 → v6（v1.9.0 借贷账户化，PRD 2.1 / 5）：
 *
 * - `account` 增加 `debtDirection` / `note` / `dueAt`，借贷从「独立台账」变成「一人一账户」；
 * - `txn` 增加 `debtAction`（四种操作 + 坏账）与 `installmentId`（还款冲抵哪个分期）；
 * - **旧 `debt` 表的数据不在这里搬**：迁移是 SQL 层面的结构变更，搬数据需要读 `debt` 再算余额、
 *   补流水、处理坏账，逻辑量远超一次 ALTER。这部分交给 `DebtToAccountMigrator`，
 *   它在库建好之后跑，且幂等（`debt` 表空了就什么都不做），备份恢复后也会再跑一次。
 *
 * 历史 `DEBT` 流水一律 `debtAction = NULL`：它们既不进余额聚合，也不会误改任何账户，
 * 迁移器会把有 `debtId` 的那部分补上正确的动作。
 */
val MIGRATION_5_6: Migration =
    object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            addColumnIfMissing(db, "account", "debtDirection", "TEXT")
            addColumnIfMissing(db, "account", "note", "TEXT NOT NULL DEFAULT ''")
            addColumnIfMissing(db, "account", "dueAt", "INTEGER")
            addColumnIfMissing(db, "txn", "debtAction", "TEXT")
            addColumnIfMissing(db, "txn", "installmentId", "TEXT")
        }
    }

/**
 * 加列前先查 `PRAGMA table_info`，已有就跳过。
 *
 * Room 的 `ALTER TABLE ... ADD COLUMN` 不支持 `IF NOT EXISTS`，列已存在时直接抛
 * `duplicate column name`，整条迁移链断掉、应用起不来。正常情况下版本号保证不会重复执行，
 * 但「迁移被手工重放」「测试里从半新库继续往上迁」都会踩到，成本极低，索性做幂等。
 */
private fun addColumnIfMissing(
    db: SupportSQLiteDatabase,
    table: String,
    column: String,
    definition: String,
) {
    val exists =
        db.query("PRAGMA table_info(`$table`)").use { c ->
            val nameIndex = c.getColumnIndexOrThrow("name")
            var found = false
            while (c.moveToNext()) {
                if (c.getString(nameIndex) == column) {
                    found = true
                    break
                }
            }
            found
        }
    if (!exists) db.execSQL("ALTER TABLE `$table` ADD COLUMN `$column` $definition")
}

/**
 * v6 → v7（v1.9.0 分期，PRD 3.2）：新建 `installment` 表。
 *
 * **`txn` 表一行都不改**——分期不产生新账，只是把一笔已记过的账重新安排还款时间。
 */
val MIGRATION_6_7: Migration =
    object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `installment` (
                    `id` TEXT NOT NULL,
                    `scope` TEXT NOT NULL,
                    `txnId` TEXT,
                    `accountId` TEXT NOT NULL,
                    `principalCents` INTEGER NOT NULL,
                    `feeCents` INTEGER NOT NULL,
                    `feeMode` TEXT NOT NULL,
                    `periods` INTEGER NOT NULL,
                    `startBillKey` INTEGER NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_installment_accountId` ON `installment` (`accountId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_installment_txnId` ON `installment` (`txnId`)")
        }
    }

/**
 * v7 → v8：账目加一列收据照片路径（PRD v1.9.1 第 5 条「相机」）。
 *
 * 用 `addColumnIfMissing` 而不是裸 ALTER，理由同 [MIGRATION_5_6]：迁移被重放时不至于炸库。
 */
val MIGRATION_7_8: Migration =
    object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            addColumnIfMissing(db, "txn", "photoPath", "TEXT")
        }
    }

/**
 * v8 → v9（v1.9.5 第 9 条）：分期加「已执行期数」与「下次生成时刻」。
 *
 * 这两列让分期从"只读的计划"变成"会自动出账的排期"：
 * `paidPeriods` 既是进度也是剩余期数的唯一来源，`nextRunAt` 给幂等引擎一个游标。
 */
val MIGRATION_8_9: Migration =
    object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            addColumnIfMissing(db, "installment", "paidPeriods", "INTEGER NOT NULL DEFAULT 0")
            addColumnIfMissing(db, "installment", "nextRunAt", "INTEGER NOT NULL DEFAULT 0")
        }
    }

/** 全部迁移，供 [AppDatabase] 构建时注册。 */
val ALL_MIGRATIONS: Array<Migration> =
    arrayOf(
        MIGRATION_1_2,
        MIGRATION_2_3,
        MIGRATION_3_4,
        MIGRATION_4_5,
        MIGRATION_5_6,
        MIGRATION_6_7,
        MIGRATION_7_8,
        MIGRATION_8_9,
    )
