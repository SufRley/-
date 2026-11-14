package com.xxjz.ledger

import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 权限审计（AC-90）：单机 App 不该申请任何网络 / 通讯录 / 短信权限。
 *
 * 这条测试的价值不在「今天是对的」，而在**以后加依赖时立刻报警**：
 * WorkManager、Coil、某些统计 SDK 都会往清单里偷偷合并权限，光看源清单看不出来。
 * 所以这里读的是**合并后**的真实清单（Robolectric 会加载 AGP 合并过的 AndroidManifest）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ManifestPermissionTest {
    private lateinit var packageManager: PackageManager
    private lateinit var packageName: String

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        packageManager = context.packageManager
        packageName = context.packageName
    }

    private fun permissions(): Set<String> {
        val info: android.content.pm.PackageInfo =
            packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
        val declared: Array<String>? = info.requestedPermissions
        return declared?.toSet().orEmpty()
    }

    @Test
    fun noInternetPermission() {
        assertFalse("不该申请 INTERNET（v1 无网络，AC-90）", permissions().contains("android.permission.INTERNET"))
    }

    @Test
    fun noNetworkStatePermission() {
        // WorkManager 会默认合并这条；虽然它本身不联网，但会让「权限列表」看起来像联网 App
        assertFalse("不该申请 ACCESS_NETWORK_STATE", permissions().contains("android.permission.ACCESS_NETWORK_STATE"))
    }

    @Test
    fun noSensitivePermissions() {
        // CAMERA 不在禁用列表里：收据拍照是 v1.9.1 起的正式功能，属于「用户可感知、
        // 点了才申请」的正当权限。真正要禁的是网络、通讯录、短信、定位与外部存储。
        val forbidden =
            listOf(
                "android.permission.READ_SMS",
                "android.permission.SEND_SMS",
                "android.permission.RECEIVE_SMS",
                "android.permission.READ_CONTACTS",
                "android.permission.WRITE_CONTACTS",
                "android.permission.READ_CALL_LOG",
                "android.permission.ACCESS_FINE_LOCATION",
                "android.permission.ACCESS_COARSE_LOCATION",
                "android.permission.READ_EXTERNAL_STORAGE",
                "android.permission.WRITE_EXTERNAL_STORAGE",
                "android.permission.READ_MEDIA_IMAGES",
                "android.permission.READ_MEDIA_VIDEO",
                "android.permission.MANAGE_EXTERNAL_STORAGE",
            )
        val granted = permissions()
        forbidden.forEach { assertFalse("不该申请 $it", granted.contains(it)) }
    }

    @Test
    fun cameraOnlyForReceiptPhoto() {
        // 相册走 PickVisualMedia（系统选择器），不需要任何存储权限；
        // 拍照走 TakePicture + ACTION_IMAGE_CAPTURE，声明了 CAMERA 就必须运行时申请，
        // 否则 startActivity 直接抛 SecurityException（v1.9.3 第 3 条）。
        val granted = permissions()
        assertTrue("收据拍照需要 CAMERA", granted.contains("android.permission.CAMERA"))
        assertFalse(
            "相册用的是系统选择器，不该带媒体读取权限",
            granted.any { it.startsWith("android.permission.READ_MEDIA") },
        )
    }

    @Test
    fun onlyExpectedPermissions() {
        // 白名单：越界说明有新依赖偷偷带权限进来，测试失败提示先来核一下这条
        // 每一项都是有意保留的：
        // POST_NOTIFICATIONS 每日提醒（AC-85）；USE_BIOMETRIC/USE_FINGERPRINT 应用锁（AC-86）；
        // WAKE_LOCK 与 RECEIVE_BOOT_COMPLETED 供 WorkManager 执行/恢复任务；
        // CAMERA 收据拍照（v1.9.1 起，v1.9.3 补运行时申请）。
        // 最后一条由 AndroidX 按 applicationId 生成（debug 包带 .debug 后缀，不能写死）。
        val allowed =
            setOf(
                "android.permission.POST_NOTIFICATIONS",
                "android.permission.USE_BIOMETRIC",
                "android.permission.USE_FINGERPRINT",
                "android.permission.WAKE_LOCK",
                "android.permission.RECEIVE_BOOT_COMPLETED",
                "android.permission.CAMERA",
                "$packageName.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
            )
        val unexpected = permissions() - allowed
        assertTrue("出现了未审计的权限：$unexpected", unexpected.isEmpty())
    }
}
