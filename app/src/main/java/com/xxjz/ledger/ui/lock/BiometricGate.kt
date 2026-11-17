package com.xxjz.ledger.ui.lock

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

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
