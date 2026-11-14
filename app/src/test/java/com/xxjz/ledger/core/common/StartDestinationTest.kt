package com.xxjz.ledger.core.common

import com.xxjz.ledger.data.datastore.AppSettings
import com.xxjz.ledger.data.datastore.SettingsRepository
import com.xxjz.ledger.testutil.FakeSettingsDataStore
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 首屏落点决策（AC-88 / AC-84）。
 *
 * 这几条断言的是「用户第一次打开 App 看到什么」，最容易出回归的地方是：
 * 引导标记读完之前就先画了主界面，以及从快捷方式进来被引导挡住。
 */
@DisplayName("首屏落点决策 StartDestination")
class StartDestinationTest {
    @Test
    @DisplayName("没引导过 → 先看引导")
    fun firstLaunchGoesOnboarding() {
        assertEquals(StartDestination.ROUTE_ONBOARDING, StartDestination.decide(onboardingDone = false))
    }

    @Test
    @DisplayName("引导过 → 直接进明细")
    fun secondLaunchGoesLedger() {
        assertEquals(StartDestination.ROUTE_LEDGER, StartDestination.decide(onboardingDone = true))
    }

    @Test
    @DisplayName("从快捷方式进来 → 无视引导，直接进明细")
    fun shortcutBypassesOnboarding() {
        listOf(ShortcutAction.NEW_EXPENSE, ShortcutAction.OPEN_REPORT, ShortcutAction.OPEN_SEARCH).forEach { action ->
            assertEquals(
                StartDestination.ROUTE_LEDGER,
                StartDestination.decide(onboardingDone = false, shortcut = action),
                "快捷方式 $action 不该被引导拦住",
            )
        }
    }

    @Test
    @DisplayName("路由常量与 Routes 保持一致，防止两边改名后静默错位")
    fun routeConstantsMatchRoutes() {
        assertEquals(com.xxjz.ledger.navigation.Routes.LEDGER, StartDestination.ROUTE_LEDGER)
        assertEquals(com.xxjz.ledger.navigation.Routes.ONBOARDING, StartDestination.ROUTE_ONBOARDING)
    }

    @Test
    @DisplayName("读引导标记：正常读到 false → false")
    fun readsFlagFromSettings() =
        runTest {
            val settings: SettingsRepository = FakeSettingsDataStore()
            assertFalse(readOnboardingDone(settings))
        }

    @Test
    @DisplayName("读引导标记：已完成后返回 true")
    fun readsTrueAfterFinish() =
        runTest {
            val settings = FakeSettingsDataStore()
            settings.setOnboardingDone(true)
            assertTrue(readOnboardingDone(settings))
        }

    @Test
    @DisplayName("超时兜底为「已完成」，宁可这次不弹引导也不白屏")
    fun timeoutFallsBackToDone() =
        runTest {
            val slow =
                object : SettingsRepository by FakeSettingsDataStore() {
                    override suspend fun current(): AppSettings {
                        // 模拟极慢的磁盘读
                        kotlinx.coroutines.delay(5_000)
                        return AppSettings()
                    }
                }
            assertTrue(readOnboardingDone(slow, timeoutMillis = 50))
        }

    @Test
    @DisplayName("读取抛异常也按已完成处理，不死循环弹引导")
    fun exceptionFallsBackToDone() =
        runTest {
            val broken =
                object : SettingsRepository by FakeSettingsDataStore() {
                    override suspend fun current(): AppSettings = error("DataStore 损坏")
                }
            assertTrue(readOnboardingDone(broken))
        }
}
