package com.xxjz.ledger

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.xxjz.ledger.core.designsystem.theme.ThemeMode
import com.xxjz.ledger.core.designsystem.theme.XxjzTheme
import com.xxjz.ledger.data.datastore.SettingsDataStore
import com.xxjz.ledger.navigation.XxjzApp
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 唯一 Activity。
 *
 * 主题模式与配色反转从 DataStore 读取（AC-41 / AC-81），
 * 在「我的 → 外观」切换后全局立即生效，不需要重启。
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var settings: SettingsDataStore

    private val appearance = MutableStateFlow(Appearance())

    /** 当前外观（主题模式 + 是否反转收支色）。 */
    data class Appearance(
        val themeMode: String = "SYSTEM",
        val amountColorsReversed: Boolean = false,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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
                XxjzApp()
            }
        }
    }
}
