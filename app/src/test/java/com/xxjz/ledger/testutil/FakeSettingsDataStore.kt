package com.xxjz.ledger.testutil

import com.xxjz.ledger.data.backup.BackupSettings
import com.xxjz.ledger.data.datastore.AppSettings
import com.xxjz.ledger.data.datastore.DefaultTimeMode
import com.xxjz.ledger.data.datastore.KeypadType
import com.xxjz.ledger.data.datastore.SettingsRepository
import com.xxjz.ledger.domain.model.TxnType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * 设置的内存实现（M9 测试用）。
 *
 * 真实实现（`SettingsDataStore`）压在 DataStore 的文件 IO 上，测试里用 MockK 去 stub
 * 十几个 setter 既啰嗦又容易漏。这里按「存进去什么、读出来就是什么」等价实现一份，
 * 越界夹断的行为与线上实现保持一致。
 */
class FakeSettingsDataStore(
    initial: AppSettings = AppSettings(),
) : SettingsRepository {
    private val flow = MutableStateFlow(initial)
    private val history = MutableStateFlow<List<String>>(emptyList())

    override val settings: kotlinx.coroutines.flow.Flow<AppSettings> = flow
    override val searchHistory: kotlinx.coroutines.flow.Flow<List<String>> = history

    /** 直接读当前值，断言用。 */
    val value: AppSettings get() = flow.value

    private fun edit(block: (AppSettings) -> AppSettings) {
        flow.update(block)
    }

    override suspend fun current(): AppSettings = flow.value

    private val collapsed = MutableStateFlow<Set<String>>(emptySet())

    override val collapsedAccountGroups: kotlinx.coroutines.flow.Flow<Set<String>> = collapsed

    override suspend fun setCollapsedAccountGroups(groups: Set<String>) {
        collapsed.value = groups
    }

    override suspend fun setDefaultAccount(id: String?) = edit { s -> s.copy(defaultAccountId = id?.takeIf { it.isNotBlank() }) }

    override suspend fun setDefaultTimeMode(mode: DefaultTimeMode) = edit { it.copy(defaultTimeMode = mode) }

    override suspend fun setFixedTimeMinutes(minutes: Int) = edit { it.copy(fixedTimeMinutes = minutes.coerceIn(0, 1439)) }

    override suspend fun setDecimalPlaces(places: Int) = edit { it.copy(decimalPlaces = places.coerceIn(0, 2)) }

    override suspend fun setKeypadType(type: KeypadType) = edit { it.copy(keypadType = type) }

    override suspend fun setAmountColorsReversed(reversed: Boolean) = edit { it.copy(amountColorsReversed = reversed) }

    override suspend fun setThemeMode(mode: String) = edit { it.copy(themeMode = mode) }

    override suspend fun setRecycleBinDays(days: Int) = edit { it.copy(recycleBinDays = days) }

    override suspend fun setAppLockEnabled(enabled: Boolean) =
        // 与真实现保持一致：关锁时联动关掉指纹开关
        edit { it.copy(appLockEnabled = enabled, biometricEnabled = if (enabled) it.biometricEnabled else false) }

    override suspend fun setBiometricEnabled(enabled: Boolean) = edit { it.copy(biometricEnabled = enabled) }

    override suspend fun setLastTxnType(type: TxnType) = edit { it.copy(lastTxnType = type) }

    override suspend fun setLastCategory(
        type: TxnType,
        categoryId: String?,
    ) = edit {
        when (type) {
            TxnType.EXPENSE -> it.copy(lastExpenseCategoryId = categoryId?.takeIf { c -> c.isNotBlank() })
            TxnType.INCOME -> it.copy(lastIncomeCategoryId = categoryId?.takeIf { c -> c.isNotBlank() })
            TxnType.TRANSFER, TxnType.DEBT -> it
        }
    }

    override suspend fun setLastPayAccount(id: String?) = edit { it.copy(lastPayAccountId = id?.takeIf { i -> i.isNotBlank() }) }

    override suspend fun setBackupReminder(enabled: Boolean) = edit { it.copy(backupReminderEnabled = enabled) }

    override suspend fun markBackedUp(at: Long) = edit { it.copy(lastBackupAt = at) }

    override suspend fun setDailyReminder(enabled: Boolean) = edit { it.copy(dailyReminderEnabled = enabled) }

    override suspend fun setDailyReminderMinute(minuteOfDay: Int) = edit { it.copy(dailyReminderMinute = minuteOfDay.coerceIn(0, 1439)) }

    override suspend fun setOnboardingDone(done: Boolean) = edit { it.copy(onboardingDone = done) }

    override suspend fun setPin(
        hash: String,
        salt: String,
    ) = edit { it.copy(pinHash = hash, pinSalt = salt) }

    override suspend fun clearPin() = edit { it.copy(pinHash = null, pinSalt = null) }

    override suspend fun setSavingGoal(cents: Long) = edit { it.copy(savingGoalCents = cents.coerceAtLeast(0)) }

    override suspend fun restoreFrom(snapshot: BackupSettings) {
        edit {
            it.copy(
                defaultAccountId = snapshot.defaultAccountId,
                defaultTimeMode = DefaultTimeMode.valueOf(snapshot.defaultTimeMode),
                fixedTimeMinutes = snapshot.fixedTimeMinutes,
                decimalPlaces = snapshot.decimalPlaces,
                keypadType = KeypadType.valueOf(snapshot.keypadType),
                amountColorsReversed = snapshot.amountColorsReversed,
                themeMode = snapshot.themeMode,
                recycleBinDays = snapshot.recycleBinDays,
                backupReminderEnabled = snapshot.backupReminderEnabled,
                lastTxnType = TxnType.valueOf(snapshot.lastTxnType),
                savingGoalCents = snapshot.savingGoalCents,
            )
        }
    }

    override suspend fun addSearchKeyword(keyword: String) {
        val t = keyword.trim()
        if (t.isEmpty()) return
        history.update { (listOf(t) + it.filter { k -> k != t }).take(10) }
    }

    override suspend fun clearSearchHistory() {
        history.value = emptyList()
    }

    /** 测试用：模拟一次搜索历史写入后的读取。 */
    suspend fun historyNow(): List<String> = history.value

    /** 测试用：彻底换成另一份快照（模拟进程重启后重新读盘）。 */
    fun replaceAll(next: AppSettings) {
        flow.value = next
    }
}
