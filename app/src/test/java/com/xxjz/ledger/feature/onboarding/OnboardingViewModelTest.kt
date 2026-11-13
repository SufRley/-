package com.xxjz.ledger.feature.onboarding

import com.xxjz.ledger.domain.model.Account
import com.xxjz.ledger.domain.model.AccountType
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

/** M9-F 首次引导（AC-88）：步骤流转、完成写入、跳过不覆盖用户设置。 */
@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {
    private val accountRepo = FakeAccountRepository()
    private lateinit var settings: FakeSettingsDataStore
    private lateinit var viewModel: OnboardingViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        settings = FakeSettingsDataStore()
        accountRepo.items["cash"] = Account(id = "cash", name = "现金", type = AccountType.FUND)
        accountRepo.items["card"] = Account(id = "card", name = "工资卡", type = AccountType.FUND)
        viewModel = OnboardingViewModel(settings, accountRepo)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun stepsForwardAndBack() =
        runTest {
            advanceUntilIdle()
            assertEquals(OnboardingStep.WELCOME, viewModel.state.value.step)
            assertEquals(1, viewModel.state.value.step.index)
            assertFalse(viewModel.state.value.canGoBack)

            viewModel.next()
            assertEquals(OnboardingStep.DEFAULT_ACCOUNT, viewModel.state.value.step)
            assertTrue(viewModel.state.value.canGoBack)

            viewModel.next()
            assertEquals(OnboardingStep.REMINDER, viewModel.state.value.step)
            assertEquals(OnboardingStep.TOTAL, viewModel.state.value.step.index)

            viewModel.back()
            assertEquals(OnboardingStep.DEFAULT_ACCOUNT, viewModel.state.value.step)
            viewModel.back()
            assertEquals(OnboardingStep.WELCOME, viewModel.state.value.step)
            // 第一步再往回退没有副作用
            viewModel.back()
            assertEquals(OnboardingStep.WELCOME, viewModel.state.value.step)
        }

    @Test
    fun preselectsFirstAccount() =
        runTest {
            advanceUntilIdle()
            assertEquals(listOf("cash", "card"), viewModel.state.value.accounts.map { it.id })
            assertEquals("cash", viewModel.state.value.selectedAccountId)
        }

    @Test
    fun reminderCheckedByDefault() =
        runTest {
            advanceUntilIdle()
            assertTrue(viewModel.state.value.reminderEnabled)
            assertEquals(21 * 60, viewModel.state.value.reminderMinute)
        }

    @Test
    fun finishWritesPreferences() =
        runTest {
            advanceUntilIdle()
            viewModel.selectAccount("card")
            viewModel.toggleReminder(false)
            viewModel.finish()
            advanceUntilIdle()

            assertTrue(viewModel.state.value.finished)
            assertEquals("card", settings.value.defaultAccountId)
            assertFalse(settings.value.dailyReminderEnabled)
            assertTrue(settings.value.onboardingDone)
        }

    @Test
    fun skipKeepsUserSettingsButMarksDone() =
        runTest {
            advanceUntilIdle()
            val before = settings.value
            viewModel.skip()
            advanceUntilIdle()

            assertTrue(viewModel.state.value.finished)
            assertTrue(settings.value.onboardingDone)
            // 跳过不该偷偷改默认账户或提醒开关
            assertEquals(before.defaultAccountId, settings.value.defaultAccountId)
            assertEquals(before.dailyReminderEnabled, settings.value.dailyReminderEnabled)
            assertNull(settings.value.defaultAccountId)
        }

    @Test
    fun finishWithoutAccountsWritesNull() =
        runTest {
            val empty = FakeSettingsDataStore()
            val vm = OnboardingViewModel(empty, FakeAccountRepository())
            advanceUntilIdle()
            vm.finish()
            advanceUntilIdle()
            assertNull(empty.value.defaultAccountId)
            assertTrue(empty.value.onboardingDone)
        }
}
