package com.xxjz.ledger.core.common

/** 应用锁的对外状态（AC-86）。 */
data class AppLockState(
    /** 锁功能是否在设置里打开。 */
    val enabled: Boolean = false,
    /** 当前是否处于「已上锁、必须解锁」的状态。 */
    val locked: Boolean = false,
    /** 连续输错次数，达到上限后只能走生物识别或干脆等超时。 */
    val failedAttempts: Int = 0,
    /** 是否设置了 PIN（没设 PIN 时只能靠生物识别兜底）。 */
    val hasPin: Boolean = false,
    /** 一次性提示，例如「密码错误，还可尝试 2 次」。 */
    val message: String? = null,
) {
    /** 输错次数是否已到上限。 */
    val blocked: Boolean get() = failedAttempts >= AppLockGuard.MAX_ATTEMPTS
}

/**
 * 应用锁状态机（AC-86）。纯逻辑，不碰 Android API，单测可直接驱动。
 *
 * 设计取舍：
 * - **每次离开前台回来都重新上锁**（v1.9.3 第 1 条）。早先有个 60 秒宽限期，
 *   结果按 Home 再点图标回来根本不锁，用户等于没有锁。
 * - **只有本 App 主动跳去的外部 Activity 豁免一次**：[beginTrustedLeave] 登记，
 *   下一次 [onForeground] 消耗掉这个名额。相机、相册、文件选择器都是独立 Activity，
 *   不豁免的话拍完照回到 App 第一眼是锁屏。
 * - **输错 5 次后进入 blocked**，此时 PIN 校验直接拒绝，避免被穷举；
 *   生物识别仍然可用（它是系统和用户指纹绑定的，猜不出来）。
 * - 如果设置里没开锁，任何操作都是 no-op，不会出现「关不掉」的情况。
 */
class AppLockGuard(
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    var state: AppLockState = AppLockState()
        private set

    /** 进入后台的时间戳；-1 表示当前在前台。 */
    private var backgroundAt: Long = -1

    /** 待消耗的外部 Activity 豁免名额。 */
    private var trustedLeaves: Int = 0

    /** 最近一次登记豁免的时间，用于兜底过期。 */
    private var trustedLeaveAt: Long = -1

    /** 设置里「是否开启应用锁」——与 [state] 的 `enabled` 不同：关掉锁之后仍需记住偏好。 */
    var lockEnabled: Boolean = false
        private set

    /**
     * 同步设置项：开关、是否设过 PIN。开启锁时立即上锁（冷启动就该先锁）。
     *
     * 没有 PIN 时**不锁**：锁了又解不开等于把用户关在门外，宁可先不锁，
     * 等用户补上 PIN 再真正生效（v1.9.3 第 1 节）。
     */
    fun configure(
        enabled: Boolean,
        hasPin: Boolean,
    ) {
        lockEnabled = enabled
        val wasEnabled = state.enabled
        state =
            state.copy(
                enabled = enabled,
                hasPin = hasPin,
                locked =
                    when {
                        !enabled || !hasPin -> false
                        wasEnabled -> state.locked
                        else -> true
                    },
                failedAttempts = if (enabled) state.failedAttempts else 0,
            )
        if (!enabled) {
            backgroundAt = -1
            trustedLeaves = 0
            trustedLeaveAt = -1
        }
    }

    /**
     * 本 App 主动跳去外部 Activity（相机 / 相册 / 文件选择器）时登记。
     *
     * 回来后不因这一次离开而锁屏；名额**只生效一次**。
     */
    fun beginTrustedLeave() {
        trustedLeaves += 1
        trustedLeaveAt = now()
    }

    /** 外部 Activity 已返回（成功、取消、失败都算）。仅用于提前归还名额。 */
    fun endTrustedLeave() {
        if (trustedLeaves > 0) trustedLeaves -= 1
    }

    /** 名额是否还在有效期内。过期视为没有——外部 Activity 被杀掉时回调永远不来。 */
    private fun hasActiveTrustedLeave(): Boolean = trustedLeaves > 0 && now() - trustedLeaveAt <= TRUSTED_LEAVE_TIMEOUT_MS

    /**
     * Activity 进前台：只要真的离开过就重新上锁，宽限期已取消（v1.9.3 第 1 条）。
     *
     * 外部 Activity 回来时消耗一次豁免，不锁。
     */
    fun onForeground() {
        if (!state.enabled) return
        val trusted = hasActiveTrustedLeave()
        trustedLeaves = 0
        trustedLeaveAt = -1
        val leftBefore = backgroundAt >= 0
        backgroundAt = -1
        if (leftBefore && !trusted && state.hasPin) {
            state = state.copy(locked = true, failedAttempts = 0)
        }
    }

    /** Activity 进后台，开始计时。外部 Activity 引发的离开不计时。 */
    fun onBackground() {
        if (!state.enabled) return
        if (hasActiveTrustedLeave()) return
        backgroundAt = now()
    }

    /** 生物识别通过。 */
    fun onBiometricSuccess() {
        if (!state.enabled) return
        state = state.copy(locked = false, failedAttempts = 0, message = null)
    }

    /** 生物识别被用户取消：保持锁定，但不计失败次数。 */
    fun onBiometricCancelled() {
        if (!state.enabled) return
        state = state.copy(locked = true, message = null)
    }

    /**
     * 用 PIN 解锁。
     *
     * @return true 解锁成功
     */
    fun unlockWithPin(
        pin: String,
        verify: (String) -> Boolean,
    ): Boolean {
        if (!state.enabled) return true
        if (state.blocked) {
            state = state.copy(message = "错误次数过多，请用指纹或稍后再试")
            return false
        }
        if (!state.hasPin) {
            state = state.copy(message = "还没有设置密码")
            return false
        }
        return if (verify(pin)) {
            onBiometricSuccess()
            true
        } else {
            val attempts = state.failedAttempts + 1
            state =
                state.copy(
                    failedAttempts = attempts,
                    locked = true,
                    message =
                        if (attempts >= MAX_ATTEMPTS) {
                            "连续输错 $MAX_ATTEMPTS 次，请用指纹解锁"
                        } else {
                            "密码错误，还可尝试 ${MAX_ATTEMPTS - attempts} 次"
                        },
                )
            false
        }
    }

    fun consumeMessage() {
        state = state.copy(message = null)
    }

    companion object {
        /**
         * 外部 Activity 豁免的有效期：10 分钟。
         *
         * 过了这个点就当没登记过——系统把相机进程杀掉时回调永远不来，
         * 名额不设上限会让锁彻底失效。
         */
        const val TRUSTED_LEAVE_TIMEOUT_MS: Long = 10 * 60_000

        /** PIN 最多连错几次。 */
        const val MAX_ATTEMPTS: Int = 5
    }
}
