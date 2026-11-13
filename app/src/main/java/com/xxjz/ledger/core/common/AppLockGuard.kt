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
 * - **切后台超过 [BACKGROUND_GRACE_MS] 才重新上锁**。从记账面板跳去相册挑图、从系统分享回来，
 *   都不该让用户重新按一次指纹；但真正离开一会儿（比如去喝咖啡）回来就该锁上。
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

    /** 设置里「是否开启应用锁」——与 [state] 的 `enabled` 不同：关掉锁之后仍需记住偏好。 */
    var lockEnabled: Boolean = false
        private set

    /** 同步设置项：开关、是否设过 PIN。开启锁时立即上锁（冷启动就该先锁）。 */
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
                        !enabled -> false
                        wasEnabled -> state.locked
                        else -> true
                    },
                failedAttempts = if (enabled) state.failedAttempts else 0,
            )
        if (!enabled) backgroundAt = -1
    }

    /** Activity 进前台：离后台超过宽限期才需要重新解锁。 */
    fun onForeground() {
        if (!state.enabled) return
        val away = if (backgroundAt < 0) 0 else now() - backgroundAt
        backgroundAt = -1
        if (away >= BACKGROUND_GRACE_MS) {
            state = state.copy(locked = true, failedAttempts = 0)
        }
    }

    /** Activity 进后台，开始计时。 */
    fun onBackground() {
        if (!state.enabled) return
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
        /** 切后台多久后回来需要重新解锁：60 秒。 */
        const val BACKGROUND_GRACE_MS: Long = 60_000

        /** PIN 最多连错几次。 */
        const val MAX_ATTEMPTS: Int = 5
    }
}
