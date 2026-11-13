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

/** 全部迁移，供 [AppDatabase] 构建时注册。 */
val ALL_MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
