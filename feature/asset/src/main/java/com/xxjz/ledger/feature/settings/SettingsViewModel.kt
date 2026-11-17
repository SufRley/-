package com.xxjz.ledger.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xxjz.ledger.core.common.PinHasher
import com.xxjz.ledger.core.common.ReminderPlanner
import com.xxjz.ledger.domain.model.Account
import com.xxjz.ledger.domain.model.AppSettings
import com.xxjz.ledger.domain.model.DefaultTimeMode
import com.xxjz.ledger.domain.model.KeypadType
import com.xxjz.ledger.domain.repository.AccountRepository
import com.xxjz.ledger.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 设置页状态（AC-95）。 */
data class SettingsState(
    val accounts: List<Account> = emptyList(),
    val prefs: AppSettings = AppSettings(),
    /** 一次性提示，例如「密码至少 4 位」。 */
    val message: String? = null,
) {
    /** 默认账户的下拉文案；账户被删了就显示「未设置」。 */
    val defaultAccountName: String
        get() = accounts.firstOrNull { it.id == prefs.defaultAccountId }?.name ?: "未设置"

    /** 提醒时间的展示文案。 */
    val reminderTimeLabel: String
        get() = ReminderPlanner.label(prefs.dailyReminderMinute)

    /** 回收站保留天数的展示文案。 */
    val recycleBinLabel: String
        get() = if (prefs.recycleBinDays <= 0) "永久保留" else "${prefs.recycleBinDays} 天"
}

/**
 * 设置页（AC-95）。
 *
 * 所有写入都直接落 DataStore，不维护「草稿」——设置项都是单选/开关，
 * 用户点完就生效才符合预期；需要二次确认的（改密码、关锁）在 UI 层弹窗。
 */
@HiltViewModel
class SettingsViewModel
    @Inject
    constructor(
        private val settings: SettingsRepository,
        accountRepository: AccountRepository,
    ) : ViewModel() {
        private val accounts = MutableStateFlow<List<Account>>(emptyList())
        private val prefs = MutableStateFlow(AppSettings())
        private val message = MutableStateFlow<String?>(null)

        /**
         * 用 Eagerly 而不是 WhileSubscribed：设置页的写入动作会读 `state.value` 做判断
         * （比如「原密码对不对」「现在是不是开着锁」），WhileSubscribed 在没人订阅时
         * 会让 `value` 停在初始的空状态，写操作就会基于错误的前提执行。
         */
        val state: StateFlow<SettingsState> =
            combine(accounts, prefs, message) { a, p, msg ->
                SettingsState(accounts = a, prefs = p, message = msg)
            }.stateIn(viewModelScope, SharingStarted.Eagerly, SettingsState())

        init {
            viewModelScope.launch {
                accountRepository.observeAll().collect { accounts.value = it }
            }
            viewModelScope.launch {
                settings.settings.collect { prefs.value = it }
            }
        }

        fun consumeMessage() {
            message.value = null
        }

        fun setDefaultAccount(id: String?) {
            viewModelScope.launch { settings.setDefaultAccount(id) }
        }

        fun setDefaultTimeMode(mode: DefaultTimeMode) {
            viewModelScope.launch { settings.setDefaultTimeMode(mode) }
        }

        /** 固定时间：只接受 0:00 ~ 23:59，越界给提示而不是静默夹断。 */
        fun setFixedTime(
            hour: Int,
            minute: Int,
        ) {
            val minutes = hour * 60 + minute
            if (!ReminderPlanner.isValid(minutes)) {
                message.value = "时间不合法"
                return
            }
            viewModelScope.launch { settings.setFixedTimeMinutes(minutes) }
        }

        fun setDecimalPlaces(places: Int) {
            if (places !in 0..2) {
                message.value = "小数位只能是 0、1、2"
                return
            }
            viewModelScope.launch {
                settings.setDecimalPlaces(places)
            }
        }

        fun setKeypadType(type: KeypadType) {
            viewModelScope.launch { settings.setKeypadType(type) }
        }

        fun setAmountColorsReversed(reversed: Boolean) {
            viewModelScope.launch { settings.setAmountColorsReversed(reversed) }
        }

        fun setThemeMode(mode: String) {
            viewModelScope.launch { settings.setThemeMode(mode) }
        }

        /** 回收站天数：0 表示永久；只接受 0 / 7 / 15 / 30 / 90。 */
        fun setRecycleBinDays(days: Int) {
            if (days != 0 && days !in ALLOWED_RECYCLE_DAYS) {
                message.value = "保留天数只能选 ${ALLOWED_RECYCLE_DAYS.joinToString("、")} 或永久"
                return
            }
            viewModelScope.launch { settings.setRecycleBinDays(days) }
        }

        fun setDailyReminder(enabled: Boolean) {
            viewModelScope.launch { settings.setDailyReminder(enabled) }
        }

        fun setReminderTime(
            hour: Int,
            minute: Int,
        ) {
            val minutes = hour * 60 + minute
            if (!ReminderPlanner.isValid(minutes)) {
                message.value = "提醒时间不合法"
                return
            }
            viewModelScope.launch { settings.setDailyReminderMinute(minutes) }
        }

        fun setBackupReminder(enabled: Boolean) {
            viewModelScope.launch { settings.setBackupReminder(enabled) }
        }

        fun setSavingGoal(yuanText: String) {
            val cents = com.xxjz.ledger.core.common.MoneyFormatter.yuanToCents(yuanText)
            if (cents == null || cents < 0) {
                message.value = "目标金额填个正数吧"
                return
            }
            viewModelScope.launch { settings.setSavingGoal(cents) }
        }

        /** 两次输入不一致时的提示（校验放在 ViewModel，保证提示文案只有一处来源）。 */
        fun setPinMismatch() {
            message.value = "两次输入的密码不一致"
        }

        /** 开启应用锁：必须同时设好 PIN，否则关掉后台再回来就只能靠指纹，指纹没录的人会被锁在外面。 */
        fun enableAppLock(pin: String) {
            if (!PinHasher.isValidFormat(pin)) {
                message.value = "密码需为 ${PinHasher.MIN_LENGTH}~${PinHasher.MAX_LENGTH} 位数字"
                return
            }
            viewModelScope.launch {
                val salt = PinHasher.newSalt()
                settings.setPin(hash = PinHasher.hash(pin, salt), salt = salt)
                settings.setAppLockEnabled(true)
                message.value = "应用锁已开启"
            }
        }

        /** 关闭应用锁：凭据一并清掉，避免残留。 */
        fun disableAppLock() {
            viewModelScope.launch {
                settings.setAppLockEnabled(false)
                settings.clearPin()
                message.value = "应用锁已关闭"
            }
        }

        /** 指纹 / 面容解锁开关（v1.8.1 #7）。 */
        fun setBiometricEnabled(enabled: Boolean) {
            viewModelScope.launch { settings.setBiometricEnabled(enabled) }
        }

        /** 设备没录入指纹时点开关的提示，由页面在禁用态下调用。 */
        fun setBiometricUnavailable() {
            message.value = "请先在系统设置中录入指纹或面容"
        }

        /** 修改密码：先校验旧密码。 */
        fun changePin(
            oldPin: String,
            newPin: String,
        ) {
            if (!PinHasher.isValidFormat(newPin)) {
                message.value = "新密码需为 ${PinHasher.MIN_LENGTH}~${PinHasher.MAX_LENGTH} 位数字"
                return
            }
            viewModelScope.launch {
                val current = settings.current()
                val ok = PinHasher.verify(oldPin, current.pinSalt.orEmpty(), current.pinHash.orEmpty())
                if (!ok) {
                    message.value = "原密码不对"
                    return@launch
                }
                val salt = PinHasher.newSalt()
                settings.setPin(hash = PinHasher.hash(newPin, salt), salt = salt)
                message.value = "密码已更新"
            }
        }

        companion object {
            val ALLOWED_RECYCLE_DAYS = listOf(7, 15, 30, 90)
        }
    }
