package com.xxjz.ledger.core.designsystem.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onParent
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import com.xxjz.ledger.core.designsystem.theme.XxjzTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 轻量金额输入框（v1.9.6 第 3 条）。
 *
 * 三条要钉住的性质：高度够 Material 最小触摸目标、整框都能聚焦、非法字符进不来。
 * 上一版把输入框强压到 52dp，热区只剩中间一条，就是「点多了之后某个框彻底点不动」的来源。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w360dp-h640dp-xhdpi")
class AmountInputTest {
    @get:Rule
    val compose = createComposeRule()

    @Composable
    private fun Host(
        initial: String = "",
        placeholder: String = "0.00",
    ) {
        var value by remember { mutableStateOf(initial) }
        XxjzTheme {
            Column {
                AmountInput(
                    value = value,
                    onValueChange = { value = it },
                    placeholder = placeholder,
                    modifier = Modifier.width(120.dp),
                    testTag = "amount",
                )
                // 回显当前值，便于断言「输入被接受成什么」
                Text(value, Modifier.testTag("echo"))
            }
        }
    }

    @Test
    fun `高度是 56dp，不低于 Material 最小触摸目标`() {
        compose.setContent { Host() }

        val height = compose.onNodeWithTag("amount").onParent().getBoundsInRoot().height
        assertTrue("实际高度 $height", height >= 56.dp)
    }

    @Test
    fun `点框的上边缘也能聚焦`() {
        compose.setContent { Host() }

        // 距顶部 2dp：上一版 52dp 强压时这一带是死的
        compose.onNodeWithTag("amount").performTouchInput { click(Offset(centerX, 2f)) }

        compose.onNodeWithTag("amount").assertIsFocused()
    }

    @Test
    fun `只收数字与一个小数点`() {
        compose.setContent { Host() }

        compose.onNodeWithTag("amount").performTextInput("1a2b3.4.5")

        compose.onNodeWithTag("echo").assertTextEquals("123.45")
    }

    @Test
    fun `超过 12 个字符截断`() {
        compose.setContent { Host() }

        compose.onNodeWithTag("amount").performTextReplacement("1234567890123456")

        compose.onNodeWithTag("echo").assertTextEquals("123456789012")
    }

    @Test
    fun `空值时显示占位符，有值时消失`() {
        compose.setContent { Host(placeholder = "0.00") }
        compose.onNodeWithText("0.00").assertExists()

        compose.onNodeWithTag("amount").performTextInput("8")
        compose.onNodeWithText("0.00").assertDoesNotExist()
    }

    @Test
    fun `过滤函数单独可测`() {
        assertEquals("12.34", sanitizeAmount("1a2.3.4"))
        assertEquals("", sanitizeAmount("abc"))
        assertEquals(".", sanitizeAmount(".."))
        assertEquals("100", sanitizeAmount("100"))
    }
}
