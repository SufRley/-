package com.xxjz.ledger.feature.mine

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xxjz.ledger.data.datastore.SettingsRepository
import com.xxjz.ledger.domain.repository.AccountRepository
import com.xxjz.ledger.domain.usecase.AssetSummary
import com.xxjz.ledger.domain.usecase.GetAssetSummaryUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** P12「我的」页状态：资产卡片数据。 */
data class MineUiState(
    val summary: AssetSummary = AssetSummary(),
    val accountCount: Int = 0,
    /** 收支配色反转（AC-41）。 */
    val amountColorsReversed: Boolean = false,
    /** 主题模式：LIGHT / DARK / SYSTEM。 */
    val themeMode: String = "SYSTEM",
)

@HiltViewModel
class MineViewModel
    @Inject
    constructor(
        accountRepository: AccountRepository,
        summaryUseCase: GetAssetSummaryUseCase,
        private val settings: SettingsRepository,
    ) : ViewModel() {
        val state: StateFlow<MineUiState> =
            combine(
                accountRepository.observeWithBalances(),
                settings.settings,
            ) { accounts, prefs ->
                MineUiState(
                    summary = summaryUseCase(accounts),
                    accountCount = accounts.size,
                    amountColorsReversed = prefs.amountColorsReversed,
                    themeMode = prefs.themeMode,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MineUiState())

        /** 切换收支配色反转，写入 DataStore 后全局生效（AC-41）。 */
        fun toggleAmountColors() {
            viewModelScope.launch {
                settings.setAmountColorsReversed(!state.value.amountColorsReversed)
            }
        }

        fun setThemeMode(mode: String) {
            viewModelScope.launch { settings.setThemeMode(mode) }
        }
    }
