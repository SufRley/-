package com.xxjz.ledger.feature.ledger

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 批量模式的返回键（v1.9.5 第 10 条）。
 *
 * 三条契约：进入批量后返回只退批量、不退应用；连按两次才退出；非批量态不拦返回。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BatchBackHandlerTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `批量模式下按返回先退出批量，不退出应用`() {
        var selecting by mutableStateOf(true)
        compose.setContent {
            BatchBackHandler(enabled = selecting) { selecting = false }
        }
        compose.waitForIdle()

        compose.activity.onBackPressedDispatcher.onBackPressed()
        compose.waitForIdle()

        assertFalse("返回应先退出批量模式", selecting)
        assertFalse("不该把 Activity 也关掉", compose.activity.isFinishing)
    }

    @Test
    fun `第二次返回才真正离开页面`() {
        var selecting by mutableStateOf(true)
        compose.setContent {
            BatchBackHandler(enabled = selecting) { selecting = false }
        }
        compose.waitForIdle()

        compose.activity.onBackPressedDispatcher.onBackPressed()
        compose.waitForIdle()
        compose.activity.onBackPressedDispatcher.onBackPressed()
        compose.waitForIdle()

        assertTrue("批量已退出，第二次返回应放行", compose.activity.isFinishing)
    }

    @Test
    fun `非批量态不拦返回`() {
        compose.setContent {
            BatchBackHandler(enabled = false) { error("不该被调用") }
        }
        compose.waitForIdle()

        compose.activity.onBackPressedDispatcher.onBackPressed()
        compose.waitForIdle()

        assertTrue("没有勾选时返回应正常关闭页面", compose.activity.isFinishing)
    }
}
