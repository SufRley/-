package com.xxjz.ledger.feature.recurring

import com.xxjz.ledger.core.common.DateUtils
import com.xxjz.ledger.domain.model.Category
import com.xxjz.ledger.domain.model.RecurringPeriod
import com.xxjz.ledger.domain.model.RecurringRule
import com.xxjz.ledger.domain.model.RecurringStatus
import com.xxjz.ledger.domain.model.TxnType
import com.xxjz.ledger.domain.usecase.RunRecurringUseCase
import com.xxjz.ledger.domain.usecase.ToggleRecurringUseCase
import com.xxjz.ledger.testutil.FakeCategoryRepository
import com.xxjz.ledger.testutil.FakeRecurringRepository
import com.xxjz.ledger.testutil.FakeTxnRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

/** M7 周期任务列表：行映射、启动补执行、暂停、立即执行、删除（AC-53、AC-54、AC-57）。 */
@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("周期任务列表")
class RecurringListViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val recurringRepo = FakeRecurringRepository()
    private val txnRepo = FakeTxnRepository()
    private val categoryRepo = FakeCategoryRepository()

    private val today: LocalDate = DateUtils.today()

    private fun rule(
        id: String,
        next: LocalDate,
        paused: Boolean = false,
        note: String = "房租",
        categoryId: String = "exp_0_0",
    ) = RecurringRule(
        id = id,
        type = TxnType.EXPENSE,
        amount = 200_000,
        categoryId = categoryId,
        accountId = "acc-1",
        note = note,
        period = RecurringPeriod.MONTH,
        nextRunAt = DateUtils.toMillis(next, LocalTime.MIDNIGHT),
        paused = paused,
    )

    private fun viewModel() =
        RecurringListViewModel(
            recurringRepo,
            categoryRepo,
            RunRecurringUseCase(recurringRepo, txnRepo),
            ToggleRecurringUseCase(recurringRepo),
        )

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        categoryRepo.categories =
            listOf(
                Category(id = "exp_0_0", name = "房租", type = TxnType.EXPENSE),
                Category(id = "exp_1_0", name = "交通", type = TxnType.EXPENSE),
            )
    }

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    @Test
    @DisplayName("启动时自动补执行，并把结果提示出来")
    fun catchesUpOnLaunch() =
        runTest {
            recurringRepo.put(rule("r1", today.minusMonths(2)))

            val vm = viewModel()
            advanceUntilIdle()

            assertEquals(3, txnRepo.items.size) // 两个月前、一个月前、今天
            assertEquals("已自动记账 3 笔", vm.state.value.message)
            assertEquals(1, vm.state.value.rows.size)

            vm.consumeMessage()
            assertNull(vm.state.value.message)
        }

    @Test
    @DisplayName("行内容：标题取备注、周期与下次执行文案正确")
    fun rowContent() =
        runTest {
            // 用「明天」避免启动补执行把游标推到下个月
            recurringRepo.put(rule("r1", today.plusDays(1)))

            val vm = viewModel()
            advanceUntilIdle()

            val row = vm.state.value.rows.single()
            assertEquals("房租", row.title)
            assertEquals("每月", row.periodLabel)
            assertEquals("明天", row.nextRunLabel)
            assertEquals(RecurringStatus.ACTIVE, row.status)
            assertEquals(1, vm.state.value.activeCount)
            assertFalse(vm.state.value.loading)
        }

    @Test
    @DisplayName("暂停后状态变已暂停，进行中计数归零")
    fun togglePause() =
        runTest {
            recurringRepo.put(rule("r1", today.plusDays(10)))
            val vm = viewModel()
            advanceUntilIdle()

            vm.toggle("r1")
            advanceUntilIdle()

            assertTrue(recurringRepo.items["r1"]!!.paused)
            assertEquals(RecurringStatus.PAUSED, vm.state.value.rows.single().status)
            assertEquals(0, vm.state.value.activeCount)
        }

    @Test
    @DisplayName("立即执行生成一笔并给出提示")
    fun runNow() =
        runTest {
            recurringRepo.put(rule("r1", today.plusDays(10)))
            val vm = viewModel()
            advanceUntilIdle()

            vm.runNow("r1")
            advanceUntilIdle()

            assertEquals(1, txnRepo.items.size)
            assertEquals("已生成一笔账目", vm.state.value.message)
        }

    @Test
    @DisplayName("删除后列表为空")
    fun delete() =
        runTest {
            recurringRepo.put(rule("r1", today.plusDays(10)))
            val vm = viewModel()
            advanceUntilIdle()

            vm.delete("r1")
            advanceUntilIdle()

            assertTrue(vm.state.value.rows.isEmpty())
            assertEquals("已删除周期任务", vm.state.value.message)
        }

    @Test
    @DisplayName("备注为空时用分类名兜底")
    fun titleFallbackToCategory() =
        runTest {
            recurringRepo.put(rule("r1", today.plusDays(10), note = "", categoryId = "exp_1_0"))
            val vm = viewModel()
            advanceUntilIdle()
            assertEquals("交通", vm.state.value.rows.single().title)
        }
}
