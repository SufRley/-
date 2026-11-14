package com.xxjz.ledger.ui.lock

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/** 生物识别的可用性（AC-86）。 */
enum class BiometricAvailability {
    /** 有硬件且用户已录入过指纹/面容。 */
    AVAILABLE,

    /** 有硬件但没录入，引导用户去系统设置里加。 */
    NOT_ENROLLED,

    /** 设备不支持（没有硬件，或系统版本太低）。 */
    UNAVAILABLE,
    ;

    companion object {
        fun of(context: Context): BiometricAvailability {
            val manager = BiometricManager.from(context)
            val authenticators =
                BiometricManager.Authenticators.BIOMETRIC_WEAK or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
            return when (manager.canAuthenticate(authenticators)) {
                BiometricManager.BIOMETRIC_SUCCESS -> AVAILABLE
                BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> NOT_ENROLLED
                else -> UNAVAILABLE
            }
        }

        /**
         * 只问「有没有录指纹/面容」，不含锁屏密码这条兜底。
         *
         * 与 [of] 的区别很实际：[of] 把 DEVICE_CREDENTIAL 也算可用，但解锁弹窗只允许
         * BIOMETRIC_WEAK（要留「用密码」按钮走我们自己的 PIN）。设备只设了锁屏密码却没录指纹时，
         * 若用 [of] 判断就会让设置里的开关看起来可用、点开却永远弹不出来。
         */
        fun hasEnrolledBiometric(context: Context): Boolean {
            val manager = BiometricManager.from(context)
            // BIOMETRIC_WEAK 同时包含 STRONG，但少数机型/ROM 对 WEAK 的判定异常，
            // 单独再问一次 STRONG 更稳（v1.9.0：用户反馈「开关灰着打不开」）。
            val weak =
                manager.canAuthenticate(
                    BiometricManager.Authenticators.BIOMETRIC_WEAK or
                        BiometricManager.Authenticators.BIOMETRIC_STRONG,
                )
            if (weak == BiometricManager.BIOMETRIC_SUCCESS) return true
            return manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
                BiometricManager.BIOMETRIC_SUCCESS
        }
    }
}

/** 抽象一层，ViewModel/Compose 里不直接依赖 FragmentActivity，也方便测试里替换。 */
interface BiometricGate {
    fun availability(): BiometricAvailability

    fun authenticate(
        onSuccess: () -> Unit,
        onFailed: () -> Unit,
    )
}

/** 真机实现：拉起系统生物识别弹窗。 */
class AndroidBiometricGate(
    private val activity: FragmentActivity,
) : BiometricGate {
    override fun availability(): BiometricAvailability = BiometricAvailability.of(activity)

    override fun authenticate(
        onSuccess: () -> Unit,
        onFailed: () -> Unit,
    ) {
        val prompt =
            BiometricPrompt(
                activity,
                ContextCompat.getMainExecutor(activity),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()

                    override fun onAuthenticationError(
                        errorCode: Int,
                        errString: CharSequence,
                    ) = onFailed()
                },
            )
        val info =
            BiometricPrompt.PromptInfo
                .Builder()
                .setTitle("解锁一心记账")
                .setNegativeButtonText("用密码")
                .setAllowedAuthenticators(
                    // 不叠 DEVICE_CREDENTIAL：那会强制去掉「用密码」按钮，
                    // 我们自己的 PIN 才是兜底路径。
                    // WEAK | STRONG 是为了兼容只声明了 3 级（Strong）指纹的机型。
                    BiometricManager.Authenticators.BIOMETRIC_WEAK or
                        BiometricManager.Authenticators.BIOMETRIC_STRONG,
                ).build()
        prompt.authenticate(info)
    }
}
