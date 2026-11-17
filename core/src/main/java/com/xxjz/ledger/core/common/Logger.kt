package com.xxjz.ledger.core.common

import android.util.Log

/**
 * 统一日志入口。
 *
 * 为什么要包一层：项目里大量「可预期的失败」（备份文件损坏、金额非法、JSON 解析失败）
 * 都用了 `runCatching` / `catch` 兜底，直接把异常吞掉会让排障无从下手；
 * 统一走这里记一条，既保留现场，又不用在每个 catch 里写 `android.util.Log` + TAG。
 *
 * Release 包整体静音：自用 App 也不该把用户数据细节打到 logcat 里给别人看，
 * 需要现场时用 debug 包复现。
 */
object Logger {
    /**
     * 全局开关，测试里可以关掉，避免 Robolectric 的日志噪音。
     *
     * 默认开启；真实构建由 [com.xxjz.ledger.XxjzApplication] 按 BuildConfig.DEBUG 设置，
     * 这样 core 模块不需要反向依赖 app（多模块下 core → app 是非法方向）。
     */
    var enabled: Boolean = true

    fun d(
        tag: String,
        message: String,
        throwable: Throwable? = null,
    ) {
        if (!enabled) return
        Log.d(tag, message, throwable)
    }

    fun w(
        tag: String,
        message: String,
        throwable: Throwable? = null,
    ) {
        if (!enabled) return
        Log.w(tag, message, throwable)
    }
}
