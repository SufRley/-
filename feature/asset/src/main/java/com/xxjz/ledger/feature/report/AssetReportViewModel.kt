package com.xxjz.ledger.feature.report

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xxjz.ledger.domain.model.Account
import com.xxjz.ledger.domain.repository.AccountRepository
import com.xxjz.ledger.domain.usecase.AssetBreakdown
import com.xxjz.ledger.domain.usecase.AssetChange
import com.xxjz.ledger.domain.usecase.GetAssetBreakdownUseCase
import com.xxjz.ledger.domain.usecase.GetAssetChangesUseCase
import com.xxjz.ledger.domain.usecase.GetLiabilityBreakdownUseCase
import com.xxjz.ledger.domain.usecase.GetNetWorthTrendUseCase
import com.xxjz.ledger.domain.usecase.LiabilityBreakdown
import com.xxjz.ledger.domain.usecase.NetWorthTrend
import com.xxjz.ledger.domain.usecase.TrendPeriod
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** 资产报表页状态（PRD 5.2 四板块）。 */
data class AssetReportUiState(
    /**
     * 原始账户列表（v1.9.3 第 5 条）。
     *
     * 「账户 / 类型」两种口径的饼图都由页面现算：切换分段控件时不该再走一遍数据库，
     * 账户列表本来就已经在内存里了。
     */
    val accounts: List<Account> = emptyList(),
    val assets: AssetBreakdown = AssetBreakdown(emptyList()),
    val liability: LiabilityBreakdown = LiabilityBreakdown(emptyList()),
    val trend: NetWorthTrend = NetWorthTrend(TrendPeriod.MONTH, emptyList()),
    val changes: List<AssetChange> = emptyList(),
    val period: TrendPeriod = TrendPeriod.MONTH,
    val loading: Boolean = true,
) {
    /** 净资产 = 资产合计 − 负债合计，与账户页同一口径。 */
    val net: Long get() = assets.total - liability.total
}

/**
 * 资产报表 ViewModel。
 *
 * 四个板块分别订阅、互不阻塞：某一板块算得慢不该拖住整页（用户先看到资产构成也合理）。
 * 趋势的月/年切换用 flatMapLatest，切走时旧流自动取消，不会留一个后台在算旧粒度。
 */
@HiltViewModel
class AssetReportViewModel
    @Inject
    constructor(
        assetBreakdown: GetAssetBreakdownUseCase,
        liabilityBreakdown: GetLiabilityBreakdownUseCase,
        private val netWorthTrend: GetNetWorthTrendUseCase,
        private val assetChanges: GetAssetChangesUseCase,
        private val accountRepository: AccountRepository,
    ) : ViewModel() {
        private val period = MutableStateFlow(TrendPeriod.MONTH)

        @OptIn(ExperimentalCoroutinesApi::class)
        private val trend = period.flatMapLatest { netWorthTrend.observe(it) }

        /** 账户列表只用于「账户 / 类型」两种口径的现算，不参与其他板块。 */
        private val accounts = accountRepository.observeWithBalances()

        val state: StateFlow<AssetReportUiState> =
            combine(
                combine(assetBreakdown.observe(), liabilityBreakdown.observe(), accounts) { a, l, acc -> Triple(a, l, acc) },
                trend,
                assetChanges.observe(),
                period,
            ) { (assets, liability, accountList), trendData, changes, period ->
                AssetReportUiState(
                    accounts = accountList,
                    assets = assets,
                    liability = liability,
                    trend = trendData,
                    changes = changes,
                    period = period,
                    loading = false,
                )
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = AssetReportUiState(),
            )

        /** 切换趋势粒度（月 / 年）。 */
        fun setPeriod(next: TrendPeriod) {
            period.value = next
        }
    }
