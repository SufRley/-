package com.xxjz.ledger.data.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.xxjz.ledger.data.backup.BackupSettings
import com.xxjz.ledger.domain.model.TxnType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

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
    ) {
        private val store: DataStore<Preferences> = context.appPreferences

        val settings: Flow<AppSettings> =
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
                    savingGoalCents = p[KEY_SAVING_GOAL] ?: 0L,
                    lastBackupAt = p[KEY_LAST_BACKUP] ?: 0L,
                )
            }

        suspend fun current(): AppSettings = settings.first()

        suspend fun setDefaultAccount(id: String?) = store.edit { it[KEY_DEFAULT_ACCOUNT] = id ?: "" }

        suspend fun setDefaultTimeMode(mode: DefaultTimeMode) = store.edit { it[KEY_DEFAULT_TIME_MODE] = mode.name }

        suspend fun setFixedTimeMinutes(minutes: Int) = store.edit { it[KEY_FIXED_TIME] = minutes }

        suspend fun setKeypadType(type: KeypadType) = store.edit { it[KEY_KEYPAD] = type.name }

        suspend fun setAmountColorsReversed(reversed: Boolean) = store.edit { it[KEY_COLOR_REVERSED] = reversed }

        suspend fun setThemeMode(mode: String) = store.edit { it[KEY_THEME_MODE] = mode }

        suspend fun setRecycleBinDays(days: Int) = store.edit { it[KEY_RECYCLE_DAYS] = days }

        suspend fun setAppLockEnabled(enabled: Boolean) = store.edit { it[KEY_LOCK] = enabled }

        suspend fun setLastTxnType(type: TxnType) = store.edit { it[KEY_LAST_TYPE] = type.name }

        /** 每周备份提醒开关（M8-T8）。 */
        suspend fun setBackupReminder(enabled: Boolean) = store.edit { it[KEY_BACKUP_REMINDER] = enabled }

        /** 记录最近一次成功导出的时间，用于首页提醒。 */
        suspend fun markBackedUp(at: Long) = store.edit { it[KEY_LAST_BACKUP] = at }

        /**
         * 用备份里的快照覆盖本机设置（AC-68）。
         *
         * 只覆盖备份里确实存在的业务设置，**不碰手势锁开关**：锁是设备本地的安全策略，
         * 跟着备份文件走等于把别人手机的锁给关了。
         */
        suspend fun restoreFrom(snapshot: BackupSettings) {
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

        /** 结余目标（分），0 表示未设置（AC-48）。 */
        suspend fun setSavingGoal(cents: Long) = store.edit { it[KEY_SAVING_GOAL] = cents.coerceAtLeast(0) }

        /** 搜索历史（最多 10 条，最近的在前）。 */
        val searchHistory: Flow<List<String>> =
            store.data.map { p ->
                p[KEY_SEARCH_HISTORY]?.split(HISTORY_SEP)?.filter { it.isNotBlank() } ?: emptyList()
            }

        suspend fun addSearchKeyword(keyword: String) {
            val trimmed = keyword.trim()
            if (trimmed.isEmpty()) return
            store.edit { p ->
                val current = p[KEY_SEARCH_HISTORY]?.split(HISTORY_SEP)?.filter { it.isNotBlank() } ?: emptyList()
                val next = (listOf(trimmed) + current.filter { it != trimmed }).take(10)
                p[KEY_SEARCH_HISTORY] = next.joinToString(HISTORY_SEP)
            }
        }

        suspend fun clearSearchHistory() = store.edit { it.remove(KEY_SEARCH_HISTORY) }

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
            val KEY_BACKUP_REMINDER = booleanPreferencesKey("backup_reminder")
            val KEY_LAST_TYPE = stringPreferencesKey("last_txn_type")
            val KEY_SEARCH_HISTORY = stringPreferencesKey("search_history")
            val KEY_SAVING_GOAL = longPreferencesKey("saving_goal_cents")
            val KEY_LAST_BACKUP = longPreferencesKey("last_backup_at")
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
    val backupReminderEnabled: Boolean = true,
    val lastTxnType: TxnType = TxnType.EXPENSE,
    /** 结余目标（分），0 表示未设置。 */
    val savingGoalCents: Long = 0,
    /** 最近一次导出备份的时间，0 表示从没备份过。 */
    val lastBackupAt: Long = 0,
)

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
    return now - lastBackupAt >= days.toLong() * 24 * 60 * 60 * 1000
}

private val Context.appPreferences: DataStore<Preferences> by preferencesDataStore(name = "xxjz_settings")
