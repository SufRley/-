package com.xxjz.ledger.ui.lock

import android.app.Activity
import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xxjz.ledger.core.common.AppLockGuard
import com.xxjz.ledger.core.common.AppLockState
import com.xxjz.ledger.core.common.PinHasher
import com.xxjz.ledger.domain.repository.SettingsRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.launch

/** 让非 Activity 层也能拿到设置，避免为了一个开关就把 SettingsRepository 到处传。 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface LockSettingsEntryPoint {
    fun settings(): SettingsRepository
}

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

private const val TAG = "AppLockHost"

/**
 * 把应用锁套在内容外面（AC-86）。
 *
 * - 生命周期事件驱动上锁/解锁计时：ON_START 回前台、ON_STOP 进后台；
 * - PIN 校验放在 IO 线程（PBKDF2 两万轮在主线程会掉帧）；
 * - 生物识别弹窗：**只有用户在设置里打开了「指纹 / 面容解锁」才会在锁定时自动拉起**（v1.8.1 #7）。
 *   没打开时仍由用户点按钮触发——自动弹会打断「切去截图再回来」这类操作，所以默认保持克制。
 */
@Composable
fun AppLockHost(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val settings = remember { EntryPointAccessors.fromApplication(context, LockSettingsEntryPoint::class.java).settings() }
    val prefs by settings.settings.collectAsStateWithLifecycle(initialValue = null)
    val guard = remember { AppLockGuard() }
    var state by remember { mutableStateOf(AppLockState()) }
    val scope = rememberCoroutineScope()
    val biometric =
        remember(context) {
            // 宿主必须是 FragmentActivity（MainActivity 已改），否则这里恒为 null，
            // 后面那句 `?: return@LaunchedEffect` 会让指纹永远静默不弹（v1.9.0 修复）。
            (context as? FragmentActivity)?.let { AndroidBiometricGate(it) }
                ?: run {
                    Log.w(TAG, "宿主不是 FragmentActivity，指纹解锁不可用：${context.javaClass.name}")
                    null
                }
        }

    // 设置变了就同步到状态机（开启后立刻要求解锁）。
    LaunchedEffect(prefs?.appLockEnabled, prefs?.hasPin) {
        prefs?.let {
            guard.configure(enabled = it.appLockEnabled, hasPin = it.hasPin)
            state = guard.state
        }
    }

    // 每个锁定周期只自动弹一次：用户取消后又手滑切走再回来，不该被反复糊一脸弹窗。
    var autoPrompted by remember { mutableStateOf(false) }
    LaunchedEffect(state.locked) {
        if (!state.locked) autoPrompted = false
    }
    LaunchedEffect(state.locked, prefs?.biometricEnabled, prefs?.appLockEnabled) {
        val p = prefs ?: return@LaunchedEffect
        if (!state.locked || !p.biometricEnabled || autoPrompted) return@LaunchedEffect
        val gate = biometric
        if (gate == null) {
            // 指纹宿主真的不可用：落一句日志，用户至少还能用密码进
            Log.w(TAG, "想自动弹指纹，但没有可用的 BiometricGate")
            return@LaunchedEffect
        }
        if (gate.availability() != BiometricAvailability.AVAILABLE) return@LaunchedEffect
        autoPrompted = true
        gate.authenticate(
            onSuccess = {
                guard.onBiometricSuccess()
                state = guard.state
            },
            onFailed = {
                // 取消/失败都落到 PIN 页，不消耗输错次数（指纹失败不该算密码输错）
                guard.onBiometricCancelled()
                state = guard.state
            },
        )
    }

    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, prefs?.appLockEnabled) {
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> {
                        guard.onForeground()
                        state = guard.state
                    }
                    Lifecycle.Event.ON_STOP -> {
                        // 转屏、分屏、深色模式切换、改字号都会走 ON_STOP → ON_START，
                        // 人根本没离开，不排除的话转一下手机就被锁（v1.9.3 第 1 条）
                        val changing = (owner as? Activity)?.isChangingConfigurations == true
                        if (!changing) guard.onBackground()
                    }
                    else -> Unit
                }
            }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    val externalGate =
        remember(guard) {
            object : ExternalActivityGate {
                override fun begin() = guard.beginTrustedLeave()

                override fun end() = guard.endTrustedLeave()
            }
        }

    CompositionLocalProvider(LocalExternalActivityGate provides externalGate) {
        Box(Modifier.fillMaxSize()) {
            content()
            if (state.locked) {
                LockScreen(
                    state = state,
                    biometricAvailable = biometric?.availability() == BiometricAvailability.AVAILABLE,
                    onUnlockBiometric = {
                        if (biometric == null) {
                            guard.onBiometricCancelled()
                            state = guard.state
                        } else {
                            biometric.authenticate(
                                onSuccess = {
                                    guard.onBiometricSuccess()
                                    state = guard.state
                                },
                                onFailed = {
                                    guard.onBiometricCancelled()
                                    state = guard.state
                                },
                            )
                        }
                    },
                    onUnlockPin = { pin ->
                        val salt = prefs?.pinSalt.orEmpty()
                        val hash = prefs?.pinHash.orEmpty()
                        scope.launch {
                            val ok =
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    PinHasher.verify(pin, salt, hash)
                                }
                            guard.unlockWithPin(pin) { ok }
                            state = guard.state
                        }
                    },
                )
            }
        }
    }
}
