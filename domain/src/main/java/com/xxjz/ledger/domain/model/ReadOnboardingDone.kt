package com.xxjz.ledger.domain.model

import com.xxjz.ledger.domain.repository.SettingsRepository
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 启动时读一次引导标记。
 *
 * 加超时是因为它挡在首屏之前：DataStore 首次读取要做磁盘 IO + proto 解析，
 * 极端情况下（低端机冷启）会比用户更快地「卡白屏」。超时就先按已完成处理直接进主界面，
 * 代价只是这次不弹引导，比白屏等着强。
 */
suspend fun readOnboardingDone(
    settings: SettingsRepository,
    timeoutMillis: Long = READ_ONBOARDING_TIMEOUT_MS,
): Boolean =
    withTimeoutOrNull(timeoutMillis) {
        runCatching { settings.current().onboardingDone }.getOrDefault(true)
    } ?: true

/** 引导标记读取的兜底超时（毫秒）。 */
const val READ_ONBOARDING_TIMEOUT_MS: Long = 800L
