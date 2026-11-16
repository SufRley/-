package com.xxjz.ledger.data.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.xxjz.ledger.data.backup.BackupSettings
import com.xxjz.ledger.domain.model.TxnType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 偏好设置的读取/写入契约。
 *
 * 抽出接口是为了让「业务规则」（如设置页的校验、备份页的提醒开关）能在纯 JVM 测试里
 * 直接换一份内存实现，不必拉起 DataStore 的文件 IO。
 * 实现见 [SettingsDataStore]；应用内通过 Hilt 拿到的仍是同一个单例。
 */
interface SettingsRepository {
    val settings: Flow<AppSettings>

    val searchHistory: Flow<List<String>>

    suspend fun current(): AppSettings

    suspend fun setDefaultAccount(id: String?)

    suspend fun setDefaultTimeMode(mode: DefaultTimeMode)

    suspend fun setFixedTimeMinutes(minutes: Int)

    suspend fun setDecimalPlaces(places: Int)

    suspend fun setKeypadType(type: KeypadType)

    suspend fun setAmountColorsReversed(reversed: Boolean)

    suspend fun setThemeMode(mode: String)

    suspend fun setRecycleBinDays(days: Int)

    suspend fun setAppLockEnabled(enabled: Boolean)

    /**
     * 指纹 / 面容解锁开关（v1.8.1）。
     *
     * 只在 [AppSettings.appLockEnabled] 为 true 时有意义：关掉应用锁会顺带关掉它。
     */
    suspend fun setBiometricEnabled(enabled: Boolean)

    suspend fun setLastTxnType(type: TxnType)

    /**
     * 记住某类型下最后一次用的分类（v1.9.3 第 2 条）。
     *
     * 支出与收入分开存：两者分类互不通用，共用一个槽位会让「切到收入」时
     * 拿上一个支出分类去匹配，结果永远是空的。
     */
    suspend fun setLastCategory(
        type: TxnType,
        categoryId: String?,
    )

    /** 记住最后一次实际用的付款账户（v1.9.3 第 2 条）。 */
    suspend fun setLastPayAccount(id: String?)

    suspend fun setBackupReminder(enabled: Boolean)

    suspend fun markBackedUp(at: Long)

    /**
     * 记下「本轮备份提示被用户关掉」的时刻（v1.9.6 第 1 条）。
     *
     * 只影响首页提示条，不动设置页的「备份提醒」总开关——关掉一条打扰，
     * 不等于把提醒功能整个关掉。
     */
    suspend fun dismissBackupHint(at: Long = System.currentTimeMillis())

    suspend fun setSavingGoal(cents: Long)

    suspend fun setDailyReminder(enabled: Boolean)

    suspend fun setDailyReminderMinute(minuteOfDay: Int)

    suspend fun setOnboardingDone(done: Boolean)

    /** 账户页折叠的分组（v1.8.0）：存枚举名集合，读出来是字符串集合。 */
    val collapsedAccountGroups: Flow<Set<String>>

    suspend fun setCollapsedAccountGroups(groups: Set<String>)

    suspend fun setPin(
        hash: String,
        salt: String,
    )

    suspend fun clearPin()

    suspend fun restoreFrom(snapshot: BackupSettings)

    suspend fun addSearchKeyword(keyword: String)

    suspend fun clearSearchHistory()
}

/** 键盘类型：计算器 / 纯数字大按钮。 */
enum class KeypadType { CALC, NUMBER }

/** 记账默认时间策略。 */
enum class DefaultTimeMode { NOW, FIXED, LAST }

/**
 * 偏好设置（AC-95），全部落在 DataStore。
 *
 * 字段与 DEV_PLAN M1-T7 对齐：默认账户、默认时间、小数位、键盘类型、配色反转、回收站天数、锁配置、备份提醒。
 */
@Singleton
class SettingsDataStore
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : SettingsRepository {
        private val store: DataStore<Preferences> = context.appPreferences

        override val settings: Flow<AppSettings> =
            store.data.map { p ->
                AppSettings(
                    defaultAccountId = p[KEY_DEFAULT_ACCOUNT],
                    defaultTimeMode =
                        p[KEY_DEFAULT_TIME_MODE]?.let { runCatching { DefaultTimeMode.valueOf(it) }.getOrNull() }
                            ?: DefaultTimeMode.NOW,
                    fixedTimeMinutes = p[KEY_FIXED_TIME] ?: DEFAULT_FIXED_MINUTES,
                    decimalPlaces = p[KEY_DECIMAL] ?: 2,
                    keypadType =
                        p[KEY_KEYPAD]?.let { runCatching { KeypadType.valueOf(it) }.getOrNull() } ?: KeypadType.CALC,
                    amountColorsReversed = p[KEY_COLOR_REVERSED] ?: false,
                    themeMode = p[KEY_THEME_MODE] ?: "SYSTEM",
                    recycleBinDays = p[KEY_RECYCLE_DAYS] ?: 30,
                    appLockEnabled = p[KEY_LOCK] ?: false,
                    backupReminderEnabled = p[KEY_BACKUP_REMINDER] ?: true,
                    lastTxnType =
                        p[KEY_LAST_TYPE]?.let { runCatching { TxnType.valueOf(it) }.getOrNull() } ?: TxnType.EXPENSE,
                    lastExpenseCategoryId = p[KEY_LAST_EXPENSE_CATEGORY],
                    lastIncomeCategoryId = p[KEY_LAST_INCOME_CATEGORY],
                    lastPayAccountId = p[KEY_LAST_PAY_ACCOUNT],
                    savingGoalCents = p[KEY_SAVING_GOAL] ?: 0L,
                    lastBackupAt = p[KEY_LAST_BACKUP] ?: 0L,
                    backupHintDismissedAt = p[KEY_BACKUP_HINT_DISMISSED] ?: 0L,
                    dailyReminderEnabled = p[KEY_DAILY_REMINDER] ?: false,
                    dailyReminderMinute = p[KEY_DAILY_REMINDER_MINUTE] ?: 21 * 60,
                    onboardingDone = p[KEY_ONBOARDING_DONE] ?: false,
                    biometricEnabled = p[KEY_BIOMETRIC] ?: false,
                    pinHash = p[KEY_PIN_HASH],
                    pinSalt = p[KEY_PIN_SALT],
                )
            }

        override suspend fun current(): AppSettings = settings.first()

        override suspend fun setDefaultAccount(id: String?) {
            store.edit { it[KEY_DEFAULT_ACCOUNT] = id ?: "" }
        }

        override suspend fun setDefaultTimeMode(mode: DefaultTimeMode) {
            store.edit { it[KEY_DEFAULT_TIME_MODE] = mode.name }
        }

        override suspend fun setFixedTimeMinutes(minutes: Int) {
            store.edit { it[KEY_FIXED_TIME] = minutes.coerceIn(0, 24 * 60 - 1) }
        }

        /** 金额小数位（0~2），记账面板与详情页统一按这个位数展示。 */
        override suspend fun setDecimalPlaces(places: Int) {
            store.edit { it[KEY_DECIMAL] = places.coerceIn(0, 2) }
        }

        override suspend fun setKeypadType(type: KeypadType) {
            store.edit { it[KEY_KEYPAD] = type.name }
        }

        override suspend fun setAmountColorsReversed(reversed: Boolean) {
            store.edit { it[KEY_COLOR_REVERSED] = reversed }
        }

        override suspend fun setThemeMode(mode: String) {
            store.edit { it[KEY_THEME_MODE] = mode }
        }

        override suspend fun setRecycleBinDays(days: Int) {
            store.edit { it[KEY_RECYCLE_DAYS] = days }
        }

        override suspend fun setAppLockEnabled(enabled: Boolean) {
            store.edit {
                it[KEY_LOCK] = enabled
                // 关掉应用锁时指纹开关必须一起关：锁都不上了，留个「指纹解锁」没有意义，
                // 下次再打开应用锁时也不该莫名其妙继承上次的开关状态。
                if (!enabled) it[KEY_BIOMETRIC] = false
            }
        }

        override suspend fun setBiometricEnabled(enabled: Boolean) {
            store.edit { it[KEY_BIOMETRIC] = enabled }
        }

        override suspend fun setLastTxnType(type: TxnType) {
            store.edit { it[KEY_LAST_TYPE] = type.name }
        }

        override suspend fun setLastCategory(
            type: TxnType,
            categoryId: String?,
        ) {
            val key =
                when (type) {
                    TxnType.EXPENSE -> KEY_LAST_EXPENSE_CATEGORY
                    TxnType.INCOME -> KEY_LAST_INCOME_CATEGORY
                    // 转账与借贷不选分类，写进去只会污染下次支出/收入的预选
                    TxnType.TRANSFER, TxnType.DEBT -> return
                }
            store.edit { it[key] = categoryId ?: "" }
        }

        override suspend fun setLastPayAccount(id: String?) {
            store.edit { it[KEY_LAST_PAY_ACCOUNT] = id ?: "" }
        }

        /** 每周备份提醒开关（M8-T8）。 */
        override suspend fun setBackupReminder(enabled: Boolean) {
            store.edit { it[KEY_BACKUP_REMINDER] = enabled }
        }

        /** 记录最近一次成功导出的时间，用于首页提醒。 */
        override suspend fun markBackedUp(at: Long) {
            store.edit { it[KEY_LAST_BACKUP] = at }
        }

        override suspend fun dismissBackupHint(at: Long) {
            store.edit { it[KEY_BACKUP_HINT_DISMISSED] = at }
        }

        /**
         * 用备份里的快照覆盖本机设置（AC-68）。
         *
         * 只覆盖备份里确实存在的业务设置，**不碰手势锁开关**：锁是设备本地的安全策略，
         * 跟着备份文件走等于把别人手机的锁给关了。
         */
        override suspend fun restoreFrom(snapshot: BackupSettings) {
            store.edit { p ->
                p[KEY_DEFAULT_ACCOUNT] = snapshot.defaultAccountId ?: ""
                p[KEY_DEFAULT_TIME_MODE] = snapshot.defaultTimeMode
                p[KEY_FIXED_TIME] = snapshot.fixedTimeMinutes
                p[KEY_DECIMAL] = snapshot.decimalPlaces
                p[KEY_KEYPAD] = snapshot.keypadType
                p[KEY_COLOR_REVERSED] = snapshot.amountColorsReversed
                p[KEY_THEME_MODE] = snapshot.themeMode
                p[KEY_RECYCLE_DAYS] = snapshot.recycleBinDays
                p[KEY_BACKUP_REMINDER] = snapshot.backupReminderEnabled
                p[KEY_LAST_TYPE] = snapshot.lastTxnType
                p[KEY_SAVING_GOAL] = snapshot.savingGoalCents
            }
        }

        /** 每日记账提醒开关（AC-85）。 */
        override suspend fun setDailyReminder(enabled: Boolean) {
            store.edit { it[KEY_DAILY_REMINDER] = enabled }
        }

        /** 每日提醒时间（当天第几分钟，0..1439）。 */
        override suspend fun setDailyReminderMinute(minuteOfDay: Int) {
            store.edit { it[KEY_DAILY_REMINDER_MINUTE] = minuteOfDay.coerceIn(0, 24 * 60 - 1) }
        }

        /** 账户页折叠分组（v1.8.0）：轻量界面状态，不入备份，恢复备份后按默认展开。 */
        override val collapsedAccountGroups: Flow<Set<String>> = store.data.map { p -> p[KEY_COLLAPSED_GROUPS] ?: emptySet() }

        override suspend fun setCollapsedAccountGroups(groups: Set<String>) {
            store.edit { it[KEY_COLLAPSED_GROUPS] = groups }
        }

        /** 首次引导是否已完成（AC-88）。 */
        override suspend fun setOnboardingDone(done: Boolean) {
            store.edit { it[KEY_ONBOARDING_DONE] = done }
        }

        /**
         * 写入手势/PIN 锁的凭据（AC-86）。
         *
         * 只存 PBKDF2 派生出的 hash 与随机盐，明文密码从不落盘，也不进备份文件。
         */
        override suspend fun setPin(
            hash: String,
            salt: String,
        ) {
            store.edit {
                it[KEY_PIN_HASH] = hash
                it[KEY_PIN_SALT] = salt
            }
        }

        /** 关掉应用锁时一并清掉凭据，避免「关了就没人管」的残留。 */
        override suspend fun clearPin() {
            store.edit {
                it.remove(KEY_PIN_HASH)
                it.remove(KEY_PIN_SALT)
            }
        }

        /** 结余目标（分），0 表示未设置（AC-48）。 */
        override suspend fun setSavingGoal(cents: Long) {
            store.edit { it[KEY_SAVING_GOAL] = cents.coerceAtLeast(0) }
        }

        /** 搜索历史（最多 10 条，最近的在前）。 */
        override val searchHistory: Flow<List<String>> =
            store.data.map { p ->
                p[KEY_SEARCH_HISTORY]?.split(HISTORY_SEP)?.filter { it.isNotBlank() } ?: emptyList()
            }

        override suspend fun addSearchKeyword(keyword: String) {
            val trimmed = keyword.trim()
            if (trimmed.isEmpty()) return
            store.edit { p ->
                val current = p[KEY_SEARCH_HISTORY]?.split(HISTORY_SEP)?.filter { it.isNotBlank() } ?: emptyList()
                val next = (listOf(trimmed) + current.filter { it != trimmed }).take(10)
                p[KEY_SEARCH_HISTORY] = next.joinToString(HISTORY_SEP)
            }
        }

        override suspend fun clearSearchHistory() {
            store.edit { it.remove(KEY_SEARCH_HISTORY) }
        }

        private companion object {
            const val DEFAULT_FIXED_MINUTES = 9 * 60
            val KEY_DEFAULT_ACCOUNT = stringPreferencesKey("default_account")
            val KEY_DEFAULT_TIME_MODE = stringPreferencesKey("default_time_mode")
            val KEY_FIXED_TIME = intPreferencesKey("fixed_time_minutes")
            val KEY_DECIMAL = intPreferencesKey("decimal_places")
            val KEY_KEYPAD = stringPreferencesKey("keypad_type")
            val KEY_COLOR_REVERSED = booleanPreferencesKey("amount_colors_reversed")
            val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
            val KEY_RECYCLE_DAYS = intPreferencesKey("recycle_bin_days")
            val KEY_LOCK = booleanPreferencesKey("app_lock")
            val KEY_BIOMETRIC = booleanPreferencesKey("app_lock_biometric")
            val KEY_BACKUP_REMINDER = booleanPreferencesKey("backup_reminder")
            val KEY_LAST_TYPE = stringPreferencesKey("last_txn_type")
            val KEY_LAST_EXPENSE_CATEGORY = stringPreferencesKey("last_expense_category")
            val KEY_LAST_INCOME_CATEGORY = stringPreferencesKey("last_income_category")
            val KEY_LAST_PAY_ACCOUNT = stringPreferencesKey("last_pay_account")
            val KEY_SEARCH_HISTORY = stringPreferencesKey("search_history")
            val KEY_SAVING_GOAL = longPreferencesKey("saving_goal_cents")
            val KEY_LAST_BACKUP = longPreferencesKey("last_backup_at")
            val KEY_BACKUP_HINT_DISMISSED = longPreferencesKey("backup_hint_dismissed_at")
            val KEY_DAILY_REMINDER = booleanPreferencesKey("daily_reminder")
            val KEY_DAILY_REMINDER_MINUTE = intPreferencesKey("daily_reminder_minute")

            /** 账户页折叠的分组枚举名（v1.8.0）。 */
            val KEY_COLLAPSED_GROUPS = stringSetPreferencesKey("account_group_collapsed")
            val KEY_ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
            val KEY_PIN_HASH = stringPreferencesKey("pin_hash")
            val KEY_PIN_SALT = stringPreferencesKey("pin_salt")
            const val HISTORY_SEP = "\u0001"
        }
    }

/** 偏好设置快照。 */
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

private val Context.appPreferences: DataStore<Preferences> by preferencesDataStore(name = "xxjz_settings")
