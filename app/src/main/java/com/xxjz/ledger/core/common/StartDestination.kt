package com.xxjz.ledger.core.common

import android.content.Context
import com.xxjz.ledger.data.datastore.SettingsRepository
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 首屏落点决策（AC-88）。
 *
 * 抽成纯函数是为了单测：导航图对「第一次启动该看到引导还是明细」这件事没有发言权。
 * [shortcut] 非空表示用户是从桌面快捷方式点进来的——这种情况下**不进引导**（AC-84）：
 * 用户点「记一笔」就是想立刻记账，弹三步引导会把人挡住。
 */
object StartDestination {
    fun decide(
        onboardingDone: Boolean,
        shortcut: ShortcutAction? = null,
    ): String =
        when {
            shortcut != null -> ROUTE_LEDGER
            onboardingDone -> ROUTE_LEDGER
            else -> ROUTE_ONBOARDING
        }

    /** 与 [com.xxjz.ledger.navigation.Routes] 保持同值；这里不引用它避免 core 反向依赖 navigation。 */
    const val ROUTE_LEDGER = "ledger"
    const val ROUTE_ONBOARDING = "onboarding"
}

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

/**
 * 申请通知权限（AC-85）：Android 13+ 才需要，低版本直接返回 true。
 *
 * 未授权时提醒任务照样排，只是 `notify` 会被系统丢掉——
 * 用户之后在系统设置里放开权限，下一次触发就能收到，不需要重排。
 */
fun hasNotificationPermission(context: Context): Boolean =
    android.os.Build.VERSION.SDK_INT < 33 ||
        androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.POST_NOTIFICATIONS,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
