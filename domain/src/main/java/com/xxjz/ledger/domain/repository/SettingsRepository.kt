package com.xxjz.ledger.domain.repository

import com.xxjz.ledger.domain.model.AppSettings
import com.xxjz.ledger.domain.model.BackupSettings
import com.xxjz.ledger.domain.model.DefaultTimeMode
import com.xxjz.ledger.domain.model.KeypadType
import com.xxjz.ledger.domain.model.TxnType
import kotlinx.coroutines.flow.Flow

/**
 * 偏好设置的读取/写入契约。
 *
 * 抽出接口是为了让「业务规则」（如设置页的校验、备份页的提醒开关）能在纯 JVM 测试里
 * 直接换一份内存实现，不必拉起 DataStore 的文件 IO。
 * 实现见 `SettingsDataStore`；应用内通过 Hilt 拿到的仍是同一个单例。
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
