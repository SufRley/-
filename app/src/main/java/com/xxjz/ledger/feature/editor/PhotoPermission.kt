package com.xxjz.ledger.feature.editor

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/** 相机权限的申请句柄。 */
class CameraGate internal constructor(
    /** 走完整流程：已授权直接放行，未授权先弹系统框。 */
    val request: () -> Unit,
)

/** 点「拍照」这一刻该做什么。 */
internal enum class CameraRequest {
    /** 已经有权限，直接开拍。 */
    LAUNCH,

    /** 被永久拒绝，再申请不会有任何反应，去引导设置。 */
    BLOCKED,

    /** 还没问过或还能再问，弹系统框。 */
    ASK,
}

/** 系统权限框关闭后的结果。 */
internal enum class CameraResult {
    GRANTED,
    DENIED,
    BLOCKED,
}

/**
 * 「点拍照」的决策。抽成纯函数是为了能单测——Compose 里那句 when 本身没法断言。
 *
 * @param hasPermission 当前是否已授予 CAMERA
 * @param everAsked 本次页面生命周期内是否问过
 * @param showsRationale 系统是否还允许再次弹框（拒绝过一次为 true，勾选不再询问后为 false）
 */
internal fun cameraRequestOf(
    hasPermission: Boolean,
    everAsked: Boolean,
    showsRationale: Boolean,
): CameraRequest =
    when {
        hasPermission -> CameraRequest.LAUNCH
        everAsked && !showsRationale -> CameraRequest.BLOCKED
        else -> CameraRequest.ASK
    }

/**
 * 权限框关闭后的决策。
 *
 * @param granted 用户是否点了「允许」
 * @param showsRationale 现在系统还愿意弹框吗
 */
internal fun cameraResultOf(
    granted: Boolean,
    showsRationale: Boolean,
): CameraResult =
    when {
        granted -> CameraResult.GRANTED
        showsRationale -> CameraResult.DENIED
        else -> CameraResult.BLOCKED
    }

/**
 * 相机权限闸门（v1.9.3 第 3 条）。
 *
 * 背景：清单里声明了 `CAMERA` 之后，`ACTION_IMAGE_CAPTURE` 就要求该权限**已授予**，
 * 否则 `startActivity` 直接抛 `SecurityException` —— 这正是「点拍照就闪退」的原因。
 * 之前整个仓库没有一行运行时申请。
 *
 * 三条出口：
 * - [onGranted]：有权限（本来就有，或刚授权）→ 启动相机
 * - [onDenied]：这次拒绝了但还能再问 → 降级到相册，别让用户卡住
 * - [onBlocked]：勾了「不再询问」→ 引导去系统设置，同时也可以降级相册
 */
@Composable
internal fun rememberCameraGate(
    onGranted: () -> Unit,
    onDenied: () -> Unit,
    onBlocked: () -> Unit,
): CameraGate {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivityOrNull() }
    val granted by rememberUpdatedState(onGranted)
    val denied by rememberUpdatedState(onDenied)
    val blocked by rememberUpdatedState(onBlocked)

    // 本次页面生命周期内是否问过：永久拒绝时系统不再弹框，得靠它区分「第一次」和「被禁了」
    var everAsked by remember { mutableStateOf(false) }

    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            when (cameraResultOf(ok, activity?.showsCameraRationale() == true)) {
                CameraResult.GRANTED -> granted()
                CameraResult.DENIED -> denied()
                CameraResult.BLOCKED -> blocked()
            }
        }

    return remember(context, launcher, activity) {
        CameraGate {
            val action =
                cameraRequestOf(
                    hasPermission =
                        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                            PackageManager.PERMISSION_GRANTED,
                    everAsked = everAsked,
                    showsRationale = activity?.showsCameraRationale() == true,
                )
            when (action) {
                CameraRequest.LAUNCH -> granted()
                CameraRequest.BLOCKED -> blocked()
                CameraRequest.ASK -> {
                    everAsked = true
                    launcher.launch(Manifest.permission.CAMERA)
                }
            }
        }
    }
}

/** 系统是否还愿意为相机权限弹框；拿不到 Activity 时按「愿意」处理，避免误判成永久拒绝。 */
private fun Activity.showsCameraRationale(): Boolean = shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)

/** Compose 里拿到的 Context 可能是 ContextWrapper，逐层剥到 Activity。 */
private tailrec fun Context.findActivityOrNull(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivityOrNull()
        else -> null
    }
