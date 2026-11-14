package com.xxjz.ledger.core.common

import android.util.Log
import com.xxjz.ledger.BuildConfig

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
    /** 全局开关，测试里可以关掉，避免 Robolectric 的日志噪音。 */
    var enabled: Boolean = BuildConfig.DEBUG

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
