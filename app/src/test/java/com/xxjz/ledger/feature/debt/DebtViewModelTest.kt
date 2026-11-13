package com.xxjz.ledger.feature.debt

import androidx.lifecycle.SavedStateHandle
import com.xxjz.ledger.core.common.DateUtils
import com.xxjz.ledger.domain.model.Debt
import com.xxjz.ledger.domain.model.DebtDirection
import com.xxjz.ledger.domain.usecase.MarkDebtBadUseCase
import com.xxjz.ledger.domain.usecase.RepayDebtUseCase
import com.xxjz.ledger.domain.usecase.SaveDebtUseCase
import com.xxjz.ledger.testutil.FakeDebtRepository
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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

/** M7 借贷台账：分组、应收应付、新建、还款、坏账、删除（AC-63 ~ AC-67）。 */
@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("借贷台账")
class DebtViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val txnRepo = FakeTxnRepository()
    private val debtRepo = FakeDebtRepository(txnRepo)

    private val today: LocalDate = DateUtils.today()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    private fun listViewModel() = DebtListViewModel(debtRepo, SaveDebtUseCase(debtRepo))

    private fun detailViewModel(id: String) =
        DebtDetailViewModel(
            debtRepo,
            txnRepo,
            RepayDebtUseCase(debtRepo, txnRepo),
            MarkDebtBadUseCase(debtRepo),
            SavedStateHandle(mapOf("debtId" to id)),
        )

    private fun debt(
        id: String,
        direction: DebtDirection = DebtDirection.LEND,
        principal: Long = 50_000,
        settled: Boolean = false,
        writtenOff: Boolean = false,
    ) = Debt(
        id = id,
        direction = direction,
        counterparty = "小王",
        principal = principal,
        accountId = "acc-1",
        startAt = DateUtils.toMillis(today.minusDays(10), LocalTime.NOON),
        dueAt = DateUtils.toMillis(today.plusDays(20), LocalTime.NOON),
        settled = settled,
        writtenOff = writtenOff,
    )

    @Test
    @DisplayName("分组：借出/借入分开，已结清收起")
    fun groups() =
        runTest {
            debtRepo.put(debt("d1"))
            debtRepo.put(debt("d2", direction = DebtDirection.BORROW, principal = 30_000))
            debtRepo.put(debt("d3", settled = true))

            val vm = listViewModel()
            advanceUntilIdle()

            assertEquals(listOf("d1"), vm.state.value.group.lend.map { it.id })
            assertEquals(listOf("d2"), vm.state.value.group.borrow.map { it.id })
            assertEquals(listOf("d3"), vm.state.value.group.settled.map { it.id })
            assertEquals(50_000, vm.state.value.group.receivable)
            assertEquals(30_000, vm.state.value.group.payable)
            assertFalse(vm.state.value.showSettled)

            vm.toggleSettled()
            assertTrue(vm.state.value.showSettled)
        }

    @Test
    @DisplayName("新建借贷：金额为 0 或对方为空只提示不落库")
    fun createValidation() =
        runTest {
            val vm = listViewModel()
            advanceUntilIdle()

            vm.create(DebtDirection.LEND, "小王", "0", null, "")
            advanceUntilIdle()
            assertEquals("请输入有效金额", vm.state.value.message)

            vm.create(DebtDirection.LEND, "  ", "100", null, "")
            advanceUntilIdle()
            assertEquals("请填写对方", vm.state.value.message)

            vm.create(DebtDirection.LEND, "小王", "100", today.plusDays(7), "借款")
            advanceUntilIdle()
            assertEquals("已记录", vm.state.value.message)
            assertEquals(1, debtRepo.items.size)
            assertEquals(10_000, debtRepo.items.getValue(debtRepo.items.keys.first()).principal)
        }

    @Test
    @DisplayName("详情：部分还款后剩余减少、记录出现")
    fun repayPartial() =
        runTest {
            debtRepo.put(debt("d1", principal = 50_000))
            val vm = detailViewModel("d1")
            advanceUntilIdle()
            assertEquals(50_000, vm.state.value.debt?.remaining)

            vm.repay("200")
            advanceUntilIdle()

            assertEquals("已还 ¥200.00", vm.state.value.message)
            assertEquals(30_000, vm.state.value.debt?.remaining)
            assertEquals(1, vm.state.value.repayments.size)
            assertFalse(vm.state.value.debt!!.settled)
        }

    @Test
    @DisplayName("详情：还满自动结清并提示")
    fun repayFull() =
        runTest {
            debtRepo.put(debt("d1", principal = 50_000))
            val vm = detailViewModel("d1")
            advanceUntilIdle()

            vm.repay("500")
            advanceUntilIdle()

            assertEquals("已结清", vm.state.value.message)
            assertTrue(vm.state.value.debt!!.settled)
            assertEquals(0, vm.state.value.debt?.remaining)
        }

    @Test
    @DisplayName("详情：超额还款被拒绝")
    fun repayTooMuch() =
        runTest {
            debtRepo.put(debt("d1", principal = 50_000))
            val vm = detailViewModel("d1")
            advanceUntilIdle()

            vm.repay("600")
            advanceUntilIdle()

            assertEquals("还款金额不能超过剩余金额", vm.state.value.message)
            assertEquals(50_000, vm.state.value.debt?.remaining)
        }

    @Test
    @DisplayName("详情：标记坏账后不可再还款")
    fun markBad() =
        runTest {
            debtRepo.put(debt("d1"))
            val vm = detailViewModel("d1")
            advanceUntilIdle()

            vm.markBad()
            advanceUntilIdle()

            assertEquals("已标记坏账", vm.state.value.message)
            assertTrue(vm.state.value.debt!!.writtenOff)

            vm.repay("100")
            advanceUntilIdle()
            assertEquals("该笔借贷已标记坏账", vm.state.value.message)
        }

    @Test
    @DisplayName("详情：删除后关闭页面")
    fun remove() =
        runTest {
            debtRepo.put(debt("d1"))
            val vm = detailViewModel("d1")
            advanceUntilIdle()

            vm.remove()
            advanceUntilIdle()

            assertTrue(vm.state.value.closed)
            assertTrue(debtRepo.items.isEmpty())
        }
}
