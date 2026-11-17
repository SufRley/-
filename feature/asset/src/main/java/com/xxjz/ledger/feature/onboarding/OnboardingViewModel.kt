package com.xxjz.ledger.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xxjz.ledger.domain.model.Account
import com.xxjz.ledger.domain.repository.AccountRepository
import com.xxjz.ledger.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 引导的三步（AC-88）。 */
enum class OnboardingStep {
    WELCOME,
    DEFAULT_ACCOUNT,
    REMINDER,
    ;

    /** 进度文案：第 n / 3 步。 */
    val index: Int get() = ordinal + 1

    companion object {
        const val TOTAL = 3
    }
}

data class OnboardingState(
    val step: OnboardingStep = OnboardingStep.WELCOME,
    val accounts: List<Account> = emptyList(),
    val selectedAccountId: String? = null,
    /** 用户是否勾选「每天提醒我记账」。 */
    val reminderEnabled: Boolean = true,
    val reminderMinute: Int = 21 * 60,
    val finished: Boolean = false,
) {
    val canGoBack: Boolean get() = step != OnboardingStep.WELCOME
}

/**
 * 首次引导（AC-88）。
 *
 * 三步：欢迎 → 选默认账户 → 设每日提醒。
 * 只在首次启动出现；「跳过」与「完成」都会写 `onboardingDone=true`，之后不再打扰。
 * 引导只读账户列表、不建账户——预置账户由 Seeder 在首启时已经建好。
 */
@HiltViewModel
class OnboardingViewModel
    @Inject
    constructor(
        private val settings: SettingsRepository,
        accountRepository: AccountRepository,
    ) : ViewModel() {
        private val _state = MutableStateFlow(OnboardingState())
        val state: StateFlow<OnboardingState> = _state.asStateFlow()

        init {
            viewModelScope.launch {
                // 只取一次就够：引导期间账户不会变（用户还没进主界面）
                val accounts = accountRepository.observeAll().first()
                _state.update {
                    it.copy(accounts = accounts, selectedAccountId = it.selectedAccountId ?: accounts.firstOrNull()?.id)
                }
            }
            viewModelScope.launch {
                val prefs = settings.current()
                // 默认勾上「每天提醒我记账」，这是引导想推用户一把的唯一一个开关
                _state.update { it.copy(reminderMinute = prefs.dailyReminderMinute, reminderEnabled = true) }
            }
        }

        fun next() {
            _state.update { s ->
                when (s.step) {
                    OnboardingStep.WELCOME -> s.copy(step = OnboardingStep.DEFAULT_ACCOUNT)
                    OnboardingStep.DEFAULT_ACCOUNT -> s.copy(step = OnboardingStep.REMINDER)
                    OnboardingStep.REMINDER -> s
                }
            }
        }

        fun back() {
            _state.update { s ->
                when (s.step) {
                    OnboardingStep.DEFAULT_ACCOUNT -> s.copy(step = OnboardingStep.WELCOME)
                    OnboardingStep.REMINDER -> s.copy(step = OnboardingStep.DEFAULT_ACCOUNT)
                    OnboardingStep.WELCOME -> s
                }
            }
        }

        fun selectAccount(id: String?) = _state.update { it.copy(selectedAccountId = id) }

        fun toggleReminder(enabled: Boolean) = _state.update { it.copy(reminderEnabled = enabled) }

        /** 完成引导：把选择写进设置，并标记已引导。 */
        fun finish() {
            viewModelScope.launch {
                val s = _state.value
                settings.setDefaultAccount(s.selectedAccountId)
                settings.setDailyReminder(s.reminderEnabled)
                settings.setOnboardingDone(true)
                _state.update { it.copy(finished = true) }
            }
        }

        /** 跳过：不动用户设置，但同样标记已引导，避免下次启动又弹一遍。 */
        fun skip() {
            viewModelScope.launch {
                settings.setOnboardingDone(true)
                _state.update { it.copy(finished = true) }
            }
        }
    }
