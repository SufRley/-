package com.xxjz.ledger.data.datastore

import com.xxjz.ledger.core.common.PinHasher
import com.xxjz.ledger.domain.model.Account
import com.xxjz.ledger.domain.model.AccountType
import com.xxjz.ledger.feature.settings.SettingsViewModel
import com.xxjz.ledger.testutil.FakeAccountRepository
import com.xxjz.ledger.testutil.FakeSettingsDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** M9-E 设置页（AC-95）：校验规则、写入落点、PIN 凭据不外泄。 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private val accountRepo = FakeAccountRepository()
    private lateinit var settings: FakeSettingsDataStore
    private lateinit var viewModel: SettingsViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        settings = FakeSettingsDataStore()
        accountRepo.items["a1"] = Account(id = "a1", name = "现金", type = AccountType.SAVINGS)
        accountRepo.items["a2"] = Account(id = "a2", name = "工资卡", type = AccountType.SAVINGS)
        viewModel = SettingsViewModel(settings, accountRepo)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun defaultAccountLabel() =
        runTest {
            advanceUntilIdle()
            assertEquals("未设置", viewModel.state.value.defaultAccountName)
            viewModel.setDefaultAccount("a2")
            advanceUntilIdle()
            assertEquals("工资卡", viewModel.state.value.defaultAccountName)
            assertEquals("a2", settings.value.defaultAccountId)
        }

    @Test
    fun fixedTimeOutOfRangeRejected() =
        runTest {
            viewModel.setFixedTime(25, 0)
            advanceUntilIdle()
            assertEquals("时间不合法", viewModel.state.value.message)
            assertEquals(9 * 60, settings.value.fixedTimeMinutes)
        }

    @Test
    fun decimalPlacesValidated() =
        runTest {
            viewModel.setDecimalPlaces(3)
            advanceUntilIdle()
            assertEquals("小数位只能是 0、1、2", viewModel.state.value.message)

            viewModel.consumeMessage()
            viewModel.setDecimalPlaces(1)
            advanceUntilIdle()
            assertEquals(1, settings.value.decimalPlaces)
        }

    @Test
    fun recycleBinDaysOptions() =
        runTest {
            advanceUntilIdle()
            assertEquals("30 天", viewModel.state.value.recycleBinLabel)

            viewModel.setRecycleBinDays(45)
            advanceUntilIdle()
            assertTrue(viewModel.state.value.message!!.contains("保留天数只能选"))

            viewModel.consumeMessage()
            viewModel.setRecycleBinDays(0)
            advanceUntilIdle()
            assertEquals("永久保留", viewModel.state.value.recycleBinLabel)
        }

    @Test
    fun reminderTimeLabel() =
        runTest {
            advanceUntilIdle()
            assertEquals("21:00", viewModel.state.value.reminderTimeLabel)
            viewModel.setReminderTime(8, 5)
            advanceUntilIdle()
            assertEquals("08:05", viewModel.state.value.reminderTimeLabel)
            assertTrue(settings.value.dailyReminderEnabled.not())
        }

    @Test
    fun reminderToggle() =
        runTest {
            viewModel.setDailyReminder(true)
            advanceUntilIdle()
            assertTrue(settings.value.dailyReminderEnabled)
            viewModel.setDailyReminder(false)
            advanceUntilIdle()
            assertFalse(settings.value.dailyReminderEnabled)
        }

    @Test
    fun enableLockRequiresValidPin() =
        runTest {
            viewModel.enableAppLock("12")
            advanceUntilIdle()
            assertEquals("密码需为 4~8 位数字", viewModel.state.value.message)
            assertFalse(settings.value.appLockEnabled)

            viewModel.consumeMessage()
            viewModel.enableAppLock("1234")
            advanceUntilIdle()
            assertTrue(settings.value.appLockEnabled)
            assertTrue(settings.value.hasPin)
            // 不存明文：hash 与 salt 都不是 "1234"
            assertFalse(settings.value.pinHash == "1234")
            assertTrue(PinHasher.verify("1234", settings.value.pinSalt!!, settings.value.pinHash!!))
        }

    @Test
    fun disableLockClearsCredential() =
        runTest {
            viewModel.enableAppLock("1234")
            advanceUntilIdle()
            viewModel.disableAppLock()
            advanceUntilIdle()
            assertFalse(settings.value.appLockEnabled)
            assertNull(settings.value.pinHash)
            assertNull(settings.value.pinSalt)
            assertEquals("应用锁已关闭", viewModel.state.value.message)
        }

    @Test
    fun biometricTogglePersists() =
        runTest {
            viewModel.enableAppLock("1234")
            advanceUntilIdle()
            viewModel.consumeMessage()

            viewModel.setBiometricEnabled(true)
            advanceUntilIdle()
            assertTrue(settings.value.biometricEnabled)
            assertTrue(viewModel.state.value.prefs.biometricEnabled)

            viewModel.setBiometricEnabled(false)
            advanceUntilIdle()
            assertFalse(settings.value.biometricEnabled)
        }

    @Test
    fun disablingLockAlsoTurnsOffBiometric() =
        runTest {
            viewModel.enableAppLock("1234")
            advanceUntilIdle()
            viewModel.setBiometricEnabled(true)
            advanceUntilIdle()
            assertTrue(settings.value.biometricEnabled)

            viewModel.disableAppLock()
            advanceUntilIdle()
            // 锁都关了还留着指纹开关，下次开锁会莫名其妙继承上次状态
            assertFalse(settings.value.biometricEnabled)
        }

    @Test
    fun biometricUnavailableShowsHint() =
        runTest {
            viewModel.setBiometricUnavailable()
            advanceUntilIdle()
            assertEquals("请先在系统设置中录入指纹或面容", viewModel.state.value.message)
        }

    @Test
    fun changePinChecksOld() =
        runTest {
            viewModel.enableAppLock("1234")
            advanceUntilIdle()
            viewModel.consumeMessage()

            viewModel.changePin("0000", "5678")
            advanceUntilIdle()
            assertEquals("原密码不对", viewModel.state.value.message)
            assertTrue(PinHasher.verify("1234", settings.value.pinSalt!!, settings.value.pinHash!!))

            viewModel.consumeMessage()
            viewModel.changePin("1234", "5678")
            advanceUntilIdle()
            assertEquals("密码已更新", viewModel.state.value.message)
            assertTrue(PinHasher.verify("5678", settings.value.pinSalt!!, settings.value.pinHash!!))
            assertFalse(PinHasher.verify("1234", settings.value.pinSalt!!, settings.value.pinHash!!))
        }

    @Test
    fun savingGoalParse() =
        runTest {
            viewModel.setSavingGoal("abc")
            advanceUntilIdle()
            assertEquals("目标金额填个正数吧", viewModel.state.value.message)

            viewModel.consumeMessage()
            viewModel.setSavingGoal("1000.5")
            advanceUntilIdle()
            assertEquals(1_000_50L, settings.value.savingGoalCents)
        }

    @Test
    fun appearanceWrites() =
        runTest {
            viewModel.setThemeMode("DARK")
            viewModel.setAmountColorsReversed(true)
            viewModel.setKeypadType(KeypadType.NUMBER)
            advanceUntilIdle()
            assertEquals("DARK", settings.value.themeMode)
            assertTrue(settings.value.amountColorsReversed)
            assertEquals(KeypadType.NUMBER, settings.value.keypadType)
        }
}
