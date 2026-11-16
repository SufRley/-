package com.xxjz.ledger.domain.repository

import com.xxjz.ledger.domain.model.BackupPayload

/**
 * 备份的整库替换与回收站物理清理（AC-73 / AC-74 / AC-76）。
 *
 * domain 用例（覆盖导入、回收站清理）只认这份契约，
 * Room / DAO / 迁移器等实现细节全部关在 data 层。
 */
interface BackupRepository {
    /** 当前数据库 schema 版本：写进备份头，导入时做版本比较。 */
    val schemaVersion: Int

    /**
     * 整库替换（覆盖导入）：清空全部业务表后写入备份内容。
     *
     * 全部动作在一个事务里——中途任何一步失败都会整体回滚，
     * 不会出现「删了一半」的灾难现场。事务提交成功后会把旧版 `debt` 表
     * 搬迁成借贷账户（幂等，空表直接跳过）。
     */
    suspend fun replaceAll(payload: BackupPayload)

    /**
     * 物理清理回收站中早于 [cutoff]（毫秒时间戳）的流水 / 账户 / 预算，
     * 顺带清掉没有归属的 `txn_tag` 孤儿关联。
     *
     * @return 清理掉的条数。
     */
    suspend fun purgeTrashBefore(cutoff: Long): Int
}
