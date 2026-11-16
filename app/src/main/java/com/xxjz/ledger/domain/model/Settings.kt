package com.xxjz.ledger.domain.model

/** 键盘类型：计算器 / 纯数字大按钮。 */
enum class KeypadType { CALC, NUMBER }

/** 记账默认时间策略。 */
enum class DefaultTimeMode { NOW, FIXED, LAST }

/**
 * 偏好设置（AC-95），全部落在 DataStore。
 *
 * 字段与 DEV_PLAN M1-T7 对齐：默认账户、默认时间、小数位、键盘类型、配色反转、回收站天数、锁配置、备份提醒。
 */
data class AppSettings(
    val defaultAccountId: String? = null,
    val defaultTimeMode: DefaultTimeMode = DefaultTimeMode.NOW,
    val fixedTimeMinutes: Int = 9 * 60,
    val decimalPlaces: Int = 2,
    val keypadType: KeypadType = KeypadType.CALC,
    val amountColorsReversed: Boolean = false,
    val themeMode: String = "SYSTEM",
    val recycleBinDays: Int = 30,
    val appLockEnabled: Boolean = false,
    /** 指纹 / 面容解锁（v1.8.1）；仅 [appLockEnabled] 为 true 时可用。 */
    val biometricEnabled: Boolean = false,
    val backupReminderEnabled: Boolean = true,
    val lastTxnType: TxnType = TxnType.EXPENSE,
    /** 上次保存支出时用的分类（v1.9.3 第 2 条）。 */
    val lastExpenseCategoryId: String? = null,
    /** 上次保存收入时用的分类（v1.9.3 第 2 条）。 */
    val lastIncomeCategoryId: String? = null,
    /** 上次保存时实际用的付款账户（v1.9.3 第 2 条），优先于 [defaultAccountId]。 */
    val lastPayAccountId: String? = null,
    /** 结余目标（分），0 表示未设置。 */
    val savingGoalCents: Long = 0,
    /** 最近一次导出备份的时间，0 表示从没备份过。 */
    val lastBackupAt: Long = 0,
    /** 首页备份提示被关掉的时刻（v1.9.6 第 1 条），0 表示没关过。 */
    val backupHintDismissedAt: Long = 0,
    /** 每日记账提醒开关（AC-85）。 */
    val dailyReminderEnabled: Boolean = false,
    /** 每日提醒时间（当天第几分钟，默认 21:00）。 */
    val dailyReminderMinute: Int = 21 * 60,
    /** 首次引导是否已完成（AC-88）。 */
    val onboardingDone: Boolean = false,
    /** PIN 锁的派生 hash；为空表示没设过 PIN。 */
    val pinHash: String? = null,
    /** PIN 锁的盐；与 [pinHash] 成对出现。 */
    val pinSalt: String? = null,
) {
    /** 是否已设置 PIN（生物识别之外的手动兜底）。 */
    val hasPin: Boolean get() = !pinHash.isNullOrBlank() && !pinSalt.isNullOrBlank()
}

/**
 * 距上次备份是否已超过 [days] 天（从没备份过也算「该提醒」）。
 *
 * 做成纯函数而不是扩展函数，方便在单元测试里直接喂时间戳验证边界。
 */
fun needsBackupReminder(
    enabled: Boolean,
    lastBackupAt: Long,
    now: Long,
    days: Int = 7,
): Boolean {
    if (!enabled) return false
    if (lastBackupAt <= 0) return true
    return now - lastBackupAt >= days.toLong() * DAY_MILLIS
}

/**
 * 首页要不要显示备份提示条（v1.9.6 第 1 条）。
 *
 * 与 [needsBackupReminder] 的区别只有一条：点过 × 之后进静默期。
 * 静默期与提醒周期同为 7 天，语义统一——**备份一次或关一次，都能安生一周**。
 *
 * 静默期的判定写在提醒判定之后，所以「关掉总开关」永远压过一切，
 * 而「刚备份过」也不会因为没关过 × 就再弹一次。
 */
fun shouldShowBackupHint(
    reminderEnabled: Boolean,
    lastBackupAt: Long,
    dismissedAt: Long,
    now: Long,
    days: Int = 7,
    cooldownDays: Int = 7,
): Boolean {
    if (!needsBackupReminder(reminderEnabled, lastBackupAt, now, days)) return false
    if (dismissedAt <= 0L) return true
    // 时钟被回拨时 now - dismissedAt 可能为负，按「还在静默期」处理，不要立刻再弹。
    return now - dismissedAt >= cooldownDays.toLong() * DAY_MILLIS
}

private const val DAY_MILLIS = 24 * 60 * 60 * 1000L
