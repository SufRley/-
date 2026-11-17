package com.xxjz.ledger.data.datastore

import com.xxjz.ledger.domain.model.shouldShowBackupHint
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 首页备份提示的显示判定（v1.9.6 第 1 条）。
 *
 * 时间一律用「现在减 N 天」构造，避免测试跑在跨天边界时抖动。
 */
@DisplayName("备份提示判定")
class BackupHintTest {
    private val day = 24 * 60 * 60 * 1000L
    private val now = 1_700_000_000_000L

    @Test
    fun `从没备份过就要提示`() {
        assertTrue(
            shouldShowBackupHint(
                reminderEnabled = true,
                lastBackupAt = 0,
                dismissedAt = 0,
                now = now,
            ),
        )
    }

    @Test
    fun `超过七天没备份要提示`() {
        assertTrue(
            shouldShowBackupHint(
                reminderEnabled = true,
                lastBackupAt = now - 8 * day,
                dismissedAt = 0,
                now = now,
            ),
        )
    }

    @Test
    fun `刚好七天也算该提醒`() {
        assertTrue(
            shouldShowBackupHint(
                reminderEnabled = true,
                lastBackupAt = now - 7 * day,
                dismissedAt = 0,
                now = now,
            ),
        )
    }

    @Test
    fun `六天前备份过就不提示`() {
        assertFalse(
            shouldShowBackupHint(
                reminderEnabled = true,
                lastBackupAt = now - 6 * day,
                dismissedAt = 0,
                now = now,
            ),
        )
    }

    @Test
    fun `总开关关掉时一律不提示`() {
        assertFalse(
            shouldShowBackupHint(
                reminderEnabled = false,
                lastBackupAt = 0,
                dismissedAt = 0,
                now = now,
            ),
        )
    }

    @Test
    fun `关掉提示后七天内静默`() {
        assertFalse(
            shouldShowBackupHint(
                reminderEnabled = true,
                lastBackupAt = 0,
                dismissedAt = now - 6 * day,
                now = now,
            ),
        )
    }

    @Test
    fun `关掉提示满七天后重新出现`() {
        assertTrue(
            shouldShowBackupHint(
                reminderEnabled = true,
                lastBackupAt = 0,
                dismissedAt = now - 7 * day,
                now = now,
            ),
        )
    }

    @Test
    fun `关掉提示期间完成备份，之后不会因为静默期过而补弹`() {
        // 关掉 × 的同时备份了：满 7 天后既没超「距上次备份 7 天」的线，
        // 也不该被当成「还欠一次提醒」
        assertFalse(
            shouldShowBackupHint(
                reminderEnabled = true,
                lastBackupAt = now - 3 * day,
                dismissedAt = now - 10 * day,
                now = now,
            ),
        )
    }

    @Test
    fun `时钟被回拨时不立刻补弹`() {
        assertFalse(
            shouldShowBackupHint(
                reminderEnabled = true,
                lastBackupAt = 0,
                dismissedAt = now + 2 * day,
                now = now,
            ),
            "关掉时间在未来的情况按「仍在静默期」处理，不能一秒后又弹出来",
        )
    }
}
