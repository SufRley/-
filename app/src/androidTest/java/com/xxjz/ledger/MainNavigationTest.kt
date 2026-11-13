package com.xxjz.ledger

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test

/** AC-01 前置：底部四个 Tab 可切换，中央「＋」可点击（仪器化测试）。 */
@OptIn(ExperimentalTestApi::class)
class MainNavigationTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun tabsAreSwitchable() {
        rule.onNodeWithTag("screen_ledger").assertIsDisplayed()
        rule.onNodeWithTag("tab_report").performClick()
        rule.onNodeWithTag("screen_report").assertIsDisplayed()
        rule.onNodeWithTag("tab_budget").performClick()
        rule.onNodeWithTag("screen_budget").assertIsDisplayed()
        rule.onNodeWithTag("tab_mine").performClick()
        rule.onNodeWithTag("screen_mine").assertIsDisplayed()
        rule.onNodeWithTag("add_button").assertIsDisplayed()
    }
}
