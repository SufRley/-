package com.xxjz.ledger.ui.lock

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 外部 Activity 的免锁通道（v1.9.3 第 1 条）。
 *
 * 相机、相册、文件选择器都是独立 Activity，拉起时本 App 必然走 ON_STOP。
 * 每次回前台都上锁之后，不豁免的话拍完照回来第一眼就是锁屏，拍照功能等于废掉。
 */
interface ExternalActivityGate {
    /** 登记一次豁免：紧接着的那次返回不锁屏，名额只生效一次。 */
    fun begin()

    /** 归还名额；没登记过就什么也不做。 */
    fun end()
}

/** 没有应用锁宿主时的空实现，调用无副作用。 */
private object NoopExternalActivityGate : ExternalActivityGate {
    override fun begin() = Unit

    override fun end() = Unit
}

/** 相机 / 相册 / 文件选择的调用点用它登记免锁。 */
val LocalExternalActivityGate = staticCompositionLocalOf<ExternalActivityGate> { NoopExternalActivityGate }
