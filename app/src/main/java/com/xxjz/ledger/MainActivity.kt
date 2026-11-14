package com.xxjz.ledger

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.xxjz.ledger.core.common.ShortcutAction
import com.xxjz.ledger.core.common.hasNotificationPermission
import com.xxjz.ledger.core.designsystem.theme.ThemeMode
import com.xxjz.ledger.core.designsystem.theme.XxjzTheme
import com.xxjz.ledger.data.datastore.SettingsRepository
import com.xxjz.ledger.navigation.XxjzApp
import com.xxjz.ledger.ui.lock.AppLockHost
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 唯一 Activity。
 *
 * 主题模式与配色反转从 DataStore 读取（AC-41 / AC-81），
 * 在「我的 → 外观」切换后全局立即生效，不需要重启。
 *
 * 桌面快捷方式（AC-84）通过 [ShortcutAction.EXTRA] 传递落点，只在冷启动/新 Intent 时消费一次，
 * 旋转屏幕等配置变更不会重复触发（否则每次转屏都会弹记账面板）。
 *
 * 必须继承 [FragmentActivity] 而不是 `ComponentActivity`：
 * `AppLockHost` 靠 `context as? FragmentActivity` 才拿得到 BiometricPrompt 的宿主，
 * 早先继承 ComponentActivity 时这个强转恒为 null，指纹开关开着也永远弹不出弹窗（v1.9.0 修复）。
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {
    @Inject
    lateinit var settings: SettingsRepository

    private val appearance = MutableStateFlow(Appearance())

    /** 快捷方式要执行的落点；被 [XxjzApp] 消费后清空。 */
    private val shortcut = MutableStateFlow<ShortcutAction?>(null)

    /** 当前外观（主题模式 + 是否反转收支色）。 */
    data class Appearance(
        val themeMode: String = "SYSTEM",
        val amountColorsReversed: Boolean = false,
    )

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        shortcut.value = ShortcutAction.fromExtra(intent.getStringExtra(ShortcutAction.EXTRA))
    }

    /**
     * 申请通知权限（AC-85）。
     *
     * 只在「提醒开着 + 还没授权」时问一次：用户关掉提醒后再问会很讨嫌。
     * 用 ActivityResultContracts.RequestPermission 而不是自己写 onRequestPermissionsResult，
     * 回调绑定在 lifecycle 上，转屏也不会丢结果。
     */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < 33 || hasNotificationPermission(this)) return
        val launcher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
        lifecycleScope.launch {
            if (settings.current().dailyReminderEnabled) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 只在首次创建时读取：配置变更重建 Activity 会带上同一个 Intent，
        // 不做这个判断会出现「转屏又弹一次记账面板」。
        shortcut.value =
            if (savedInstanceState == null) {
                ShortcutAction.fromExtra(intent?.getStringExtra(ShortcutAction.EXTRA))
            } else {
                null
            }
        enableEdgeToEdge()
        requestNotificationPermissionIfNeeded()
        lifecycleScope.launch {
            settings.settings.collect { prefs ->
                appearance.value =
                    Appearance(
                        themeMode = prefs.themeMode,
                        amountColorsReversed = prefs.amountColorsReversed,
                    )
            }
        }
        setContent {
            val prefs: Appearance by appearance.collectAsStateWithLifecycle()
            XxjzTheme(
                mode =
                    when (prefs.themeMode) {
                        "LIGHT" -> ThemeMode.LIGHT
                        "DARK" -> ThemeMode.DARK
                        else -> ThemeMode.SYSTEM
                    },
                amountColorsReversed = prefs.amountColorsReversed,
            ) {
                AppLockHost {
                    XxjzApp(
                        shortcut = shortcut.collectAsStateWithLifecycle().value,
                        onShortcutConsumed = { shortcut.value = null },
                        settings = settings,
                    )
                }
            }
        }
    }
}
