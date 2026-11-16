package com.xxjz.ledger.feature.split

import androidx.lifecycle.SavedStateHandle
import com.xxjz.ledger.core.common.DateUtils
import com.xxjz.ledger.domain.model.Account
import com.xxjz.ledger.domain.model.AccountType
import com.xxjz.ledger.domain.model.Txn
import com.xxjz.ledger.domain.model.TxnType
import com.xxjz.ledger.domain.usecase.SplitTxnUseCase
import com.xxjz.ledger.navigation.Routes
import com.xxjz.ledger.testutil.FakeAccountRepository
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
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

/** 拆分明细页（v1.9.5 第 8 条）。 */
@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("拆分明细页")
class SplitTxnViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val txnRepo = FakeTxnRepository()
    private val accountRepo = FakeAccountRepository()
    private val now = DateUtils.toMillis(LocalDateTime.of(2026, 11, 16, 12, 0))

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        accountRepo.items["a"] = Account(id = "a", name = "招行", type = AccountType.SAVINGS, sortOrder = 0)
        accountRepo.items["b"] = Account(id = "b", name = "现金", type = AccountType.CASH, sortOrder = 1)
        accountRepo.items["inv"] = Account(id = "inv", name = "证券", type = AccountType.INVEST, sortOrder = 2)
        txnRepo.items["t1"] =
            Txn(
                id = "t1",
                type = TxnType.EXPENSE,
                amount = 30_000,
                categoryId = "food",
                accountId = "a",
                dateTime = now,
                dateKey = DateUtils.dateKeyOf(now),
                note = "午饭",
                createdAt = now,
                updatedAt = now,
            )
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(txnId: String = "t1"): SplitTxnViewModel =
        SplitTxnViewModel(
            txnRepository = txnRepo,
            accountRepository = accountRepo,
            splitTxn = SplitTxnUseCase(txnRepo, accountRepo),
            savedStateHandle = SavedStateHandle(mapOf(Routes.ARG_TXN_ID to txnId)),
        )

    @Test
    fun `载入后默认两行，第一行预填原账户与全额`() =
        runTest(dispatcher.scheduler) {
            val vm = viewModel()
            advanceUntilIdle()

            val state = vm.state.value
            assertEquals(2, state.rows.size)
            assertEquals("a", state.rows[0].accountId)
            assertEquals(30_000L, state.rows[0].cents)
            assertNull(state.rows[1].accountId)
            assertEquals("", state.rows[1].amountText)
        }

    @Test
    fun `账户候选只有可付款账户，不含投资`() =
        runTest(dispatcher.scheduler) {
            val vm = viewModel()
            advanceUntilIdle()

            assertEquals(listOf("a", "b"), vm.state.value.accounts.map { it.id })
        }

    @Test
    fun `合计不足时提示还差多少并禁止保存`() =
        runTest(dispatcher.scheduler) {
            val vm = viewModel()
            advanceUntilIdle()
            vm.setAmount(vm.state.value.rows[0].key, "200")
            val key = vm.state.value.rows[1].key
            vm.setAccount(key, "b")
            vm.setAmount(key, "50")

            val state = vm.state.value
            assertEquals(25_000L, state.totalCents)
            assertEquals(-5_000L, state.diffCents)
            assertEquals("还差 ¥50.00", state.blocker)
        }

    @Test
    fun `其他行超出原额时原账户归零而不是负数`() =
        runTest(dispatcher.scheduler) {
            val vm = viewModel()
            advanceUntilIdle()
            val key = vm.state.value.rows[1].key
            vm.setAccount(key, "b")
            vm.setAmount(key, "400")

            val state = vm.state.value
            assertEquals(0L, state.rows[0].cents, "负数金额没有意义，超出部分交给提示")
            assertEquals("0", state.rows[0].amountText)
            assertEquals("超出 ¥100.00", state.blocker)
        }

    @Test
    fun `合计对上后可保存`() =
        runTest(dispatcher.scheduler) {
            val vm = viewModel()
            advanceUntilIdle()
            vm.setAmount(vm.state.value.rows[0].key, "200")
            val key = vm.state.value.rows[1].key
            vm.setAccount(key, "b")
            vm.setAmount(key, "100")

            assertNull(vm.state.value.blocker)
        }

    @Test
    fun `没选账户时不放行`() =
        runTest(dispatcher.scheduler) {
            val vm = viewModel()
            advanceUntilIdle()
            vm.setAmount(vm.state.value.rows[0].key, "200")
            vm.setAmount(vm.state.value.rows[1].key, "100")

            assertEquals("每条明细都要选账户", vm.state.value.blocker)
        }

    @Test
    fun `保存成功后原账进回收站并生成两笔`() =
        runTest(dispatcher.scheduler) {
            val vm = viewModel()
            advanceUntilIdle()
            vm.setAmount(vm.state.value.rows[0].key, "200")
            val key = vm.state.value.rows[1].key
            vm.setAccount(key, "b")
            vm.setAmount(key, "100")
            vm.save()
            advanceUntilIdle()

            assertTrue(txnRepo.items.getValue("t1").isDeleted)
            val parts = txnRepo.items.values.filter { !it.isDeleted && it.id != "t1" }
            assertEquals(2, parts.size)
            assertEquals(setOf("a", "b"), parts.map { it.accountId }.toSet())
        }

    @Test
    fun `原账不存在时状态里带出原因且不能保存`() =
        runTest(dispatcher.scheduler) {
            val vm = viewModel("nope")
            advanceUntilIdle()

            assertNotNull(vm.state.value.error)
            assertNotNull(vm.state.value.blocker)
        }

    @Test
    fun `增加明细最多到 20 条`() =
        runTest(dispatcher.scheduler) {
            val vm = viewModel()
            advanceUntilIdle()

            repeat(18) { vm.addRow() }
            assertEquals(20, vm.state.value.rows.size)
            assertFalse(vm.state.value.canAdd)

            vm.addRow()
            assertEquals(20, vm.state.value.rows.size)
        }

    @Test
    fun `只剩两条时不能再删`() =
        runTest(dispatcher.scheduler) {
            val vm = viewModel()
            advanceUntilIdle()
            vm.removeRow(vm.state.value.rows[0].key)

            assertEquals(2, vm.state.value.rows.size, "已经是两条，再删就不成拆分了")
        }

    // ---------- 原账户自动跟随（v1.9.6 第 3 条） ----------

    @Test
    fun `填第二行 30 时原账户自动让出 70`() =
        runTest(dispatcher.scheduler) {
            val vm = viewModel()
            advanceUntilIdle()
            val key = vm.state.value.rows[1].key
            vm.setAccount(key, "b")
            vm.setAmount(key, "30")

            val state = vm.state.value
            assertEquals("270", state.rows[0].amountText, "原额 300，第二行 30，余 270")
            assertEquals(30_000L, state.totalCents)
            assertNull(state.blocker)
        }

    @Test
    fun `第二行改 60 时原账户跟着变 40`() =
        runTest(dispatcher.scheduler) {
            val vm = viewModel()
            advanceUntilIdle()
            val key = vm.state.value.rows[1].key
            vm.setAccount(key, "b")
            vm.setAmount(key, "30")
            vm.setAmount(key, "60")

            assertEquals("240", vm.state.value.rows[0].amountText)
        }

    @Test
    fun `手动改原账户后不再被自动覆盖`() =
        runTest(dispatcher.scheduler) {
            val vm = viewModel()
            advanceUntilIdle()
            vm.setAmount(vm.state.value.rows[0].key, "200")
            val key = vm.state.value.rows[1].key
            vm.setAccount(key, "b")
            vm.setAmount(key, "50")

            val state = vm.state.value
            assertEquals("200", state.rows[0].amountText, "用户填的 200 不能被联动改回去")
            assertEquals("还差 ¥50.00", state.blocker)
            assertFalse(state.autoBalance)
        }

    @Test
    fun `三行拆分时原账户承接剩余`() =
        runTest(dispatcher.scheduler) {
            val vm = viewModel()
            advanceUntilIdle()
            vm.addRow()
            val second = vm.state.value.rows[1].key
            val third = vm.state.value.rows[2].key
            vm.setAccount(second, "b")
            vm.setAccount(third, "a")
            vm.setAmount(second, "30")
            vm.setAmount(third, "20")

            val state = vm.state.value
            assertEquals("250", state.rows[0].amountText, "300 − 30 − 20")
            assertEquals(30_000L, state.totalCents)
            assertNull(state.blocker)
        }

    @Test
    fun `删掉原账户行后各填各的，只提示差额`() =
        runTest(dispatcher.scheduler) {
            val vm = viewModel()
            advanceUntilIdle()
            // 先加一行，才能删掉原账户行（至少要留两条）
            vm.addRow()
            vm.removeRow(vm.state.value.rows[0].key)

            vm.state.value.rows.forEachIndexed { index, row ->
                vm.setAccount(row.key, if (index == 0) "a" else "b")
            }

            val state = vm.state.value
            assertFalse(state.autoBalance, "没有原账户行了，无从联动")
            assertEquals(2, state.rows.size)
            assertEquals("还差 ¥300.00", state.blocker)
        }
}
