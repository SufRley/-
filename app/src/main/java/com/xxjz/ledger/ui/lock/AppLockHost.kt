package com.xxjz.ledger.ui.lock

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import com.xxjz.ledger.data.datastore.SettingsRepository
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
        remember {
            (context as? FragmentActivity)?.let { AndroidBiometricGate(it) }
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
        val gate = biometric ?: return@LaunchedEffect
        if (!state.locked || !p.biometricEnabled || autoPrompted) return@LaunchedEffect
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
                    Lifecycle.Event.ON_STOP -> guard.onBackground()
                    else -> Unit
                }
            }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

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
