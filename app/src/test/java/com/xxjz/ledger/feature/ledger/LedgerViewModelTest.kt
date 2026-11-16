package com.xxjz.ledger.feature.ledger

import com.xxjz.ledger.core.common.DateUtils
import com.xxjz.ledger.data.datastore.AppSettings
import com.xxjz.ledger.domain.model.Txn
import com.xxjz.ledger.domain.model.TxnType
import com.xxjz.ledger.domain.usecase.GetBudgetForMonthUseCase
import com.xxjz.ledger.domain.usecase.StartInstallmentUseCase
import com.xxjz.ledger.testutil.FakeAccountRepository
import com.xxjz.ledger.testutil.FakeBudgetRepository
import com.xxjz.ledger.testutil.FakeCategoryRepository
import com.xxjz.ledger.testutil.FakeInstallmentRepository
import com.xxjz.ledger.testutil.FakeSettingsDataStore
import com.xxjz.ledger.testutil.FakeTemplateRepository
import com.xxjz.ledger.testutil.FakeTxnRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * 明细页 ViewModel（v1.9.4 第 4 条：左滑删除移除后，删除只剩长按抽屉一条路）。
 *
 * 只测与删除相关的契约——列表分组逻辑在 [LedgerGrouperTest] 里单独覆盖。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("明细页删除")
class LedgerViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val txnRepo = FakeTxnRepository()
    private val categoryRepo = FakeCategoryRepository()
    private val templateRepo = FakeTemplateRepository()
    private val settings = FakeSettingsDataStore()

    private val today: LocalDate = DateUtils.today()
    private val now: Long = DateUtils.toMillis(today, LocalTime.NOON)

    private fun viewModel() =
        LedgerViewModel(
            txnRepository = txnRepo,
            categoryRepository = categoryRepo,
            templateRepository = templateRepo,
            budgetForMonth = GetBudgetForMonthUseCase(FakeBudgetRepository()),
            accountRepository = FakeAccountRepository(),
            installmentRepository = FakeInstallmentRepository(),
            startInstallmentUseCase =
                StartInstallmentUseCase(txnRepo, FakeAccountRepository(), FakeInstallmentRepository()),
            settingsRepository = settings,
        )

    private fun txn(id: String) =
        Txn(
            id = id,
            type = TxnType.EXPENSE,
            amount = 3_000,
            categoryId = "food",
            accountId = "acc-1",
            dateTime = now,
            dateKey = DateUtils.dateKeyOf(today),
            note = "午饭",
            createdAt = now,
            updatedAt = now,
        ).also { txnRepo.items[id] = it }

    @BeforeEach
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    @Test
    @DisplayName("删除是软删除：进回收站而不是物理抹掉")
    fun deleteIsSoft() =
        runTest(dispatcher.scheduler) {
            txn("t1")
            val vm = viewModel()
            val job = launch { vm.state.collect {} }
            advanceUntilIdle()

            vm.delete("t1")
            advanceUntilIdle()
            job.cancel()

            val stored = txnRepo.items.getValue("t1")
            assertNotNull(stored.deletedAt, "必须打上删除时间，否则回收站里找不到它")
            assertTrue(stored.isDeleted)
        }

    @Test
    @DisplayName("删除后提示「已移入回收站」，与左滑删除时代一致")
    fun deleteEmitsTrashMessage() =
        runTest(dispatcher.scheduler) {
            txn("t1")
            val vm = viewModel()
            advanceUntilIdle()

            val messages = mutableListOf<String>()
            val job = launch { vm.messages.toList(messages) }
            advanceUntilIdle()

            vm.delete("t1")
            advanceUntilIdle()

            assertEquals("已移入回收站", messages.lastOrNull())
            job.cancel()
        }

    @Test
    @DisplayName("删除后从当月列表消失")
    fun deletedTxnLeavesList() =
        runTest(dispatcher.scheduler) {
            txn("t1")
            txn("t2")
            val vm = viewModel()
            // state 用的是 WhileSubscribed：没有订阅者时永远是 initialValue，得先有一个收集者
            val job = launch { vm.state.collect {} }
            advanceUntilIdle()
            assertEquals(2, vm.state.value.count)

            vm.delete("t1")
            advanceUntilIdle()

            assertEquals(1, vm.state.value.count)
            assertEquals(listOf("t2"), vm.state.value.groups.flatMap { it.rows }.map { it.txn.id })
            job.cancel()
        }

    // ---------- 四格汇总（v1.9.5 第 5 条） ----------

    @Test
    fun `待报销合计只算本月支出，不含收入与转账`() =
        runTest(dispatcher.scheduler) {
            txnRepo.items["a"] = txn("a").copy(amount = 1000, pendingReimbursement = true)
            txnRepo.items["b"] = txn("b").copy(amount = 2500, pendingReimbursement = true)
            txnRepo.items["c"] = txn("c").copy(amount = 900, pendingReimbursement = false)
            txnRepo.items["d"] =
                txn("d").copy(type = TxnType.INCOME, amount = 5000, pendingReimbursement = true)

            val vm = viewModel()
            backgroundScope.launch { vm.state.collect {} }
            advanceUntilIdle()

            assertEquals(3500L, vm.state.value.pendingTotal)
            assertEquals(2, vm.state.value.pendingCount)
        }

    @Test
    fun `待报销不看是否计入统计`() =
        runTest(dispatcher.scheduler) {
            txnRepo.items["a"] = txn("a").copy(amount = 1200, pendingReimbursement = true, excludedFromStats = true)

            val vm = viewModel()
            backgroundScope.launch { vm.state.collect {} }
            advanceUntilIdle()

            assertEquals(1200L, vm.state.value.pendingTotal, "被排除统计的那笔照样能报销")
            assertEquals(1, vm.state.value.pendingCount)
            assertEquals(0L, vm.state.value.expense, "但它不进本月支出")
        }

    @Test
    fun `没有待报销时合计为 0`() =
        runTest(dispatcher.scheduler) {
            txnRepo.items["a"] = txn("a").copy(amount = 1000)

            val vm = viewModel()
            backgroundScope.launch { vm.state.collect {} }
            advanceUntilIdle()

            assertEquals(0L, vm.state.value.pendingTotal)
            assertEquals(0, vm.state.value.pendingCount)
        }

    @Test
    fun `从没备份过时提示去导出`() =
        runTest(dispatcher.scheduler) {
            settings.replaceAll(AppSettings(lastBackupAt = 0))

            val vm = viewModel()
            backgroundScope.launch { vm.state.collect {} }
            advanceUntilIdle()

            assertEquals("还没有备份过，建议导出一份", vm.state.value.backupHint)
        }

    @Test
    fun `超过七天没备份时提示该备份了`() =
        runTest(dispatcher.scheduler) {
            settings.replaceAll(AppSettings(lastBackupAt = System.currentTimeMillis() - 12L * DAY))

            val vm = viewModel()
            backgroundScope.launch { vm.state.collect {} }
            advanceUntilIdle()

            assertEquals("距上次备份已超过 7 天", vm.state.value.backupHint)
        }

    @Test
    fun `刚备份过就不提示`() =
        runTest(dispatcher.scheduler) {
            settings.replaceAll(AppSettings(lastBackupAt = System.currentTimeMillis() - 2L * DAY))

            val vm = viewModel()
            backgroundScope.launch { vm.state.collect {} }
            advanceUntilIdle()

            assertNull(vm.state.value.backupHint)
        }

    @Test
    fun `关掉提示后立刻消失且记下时间`() =
        runTest(dispatcher.scheduler) {
            settings.replaceAll(AppSettings(lastBackupAt = 0))
            val vm = viewModel()
            backgroundScope.launch { vm.state.collect {} }
            advanceUntilIdle()
            assertNotNull(vm.state.value.backupHint, "一开始应该是显示的")

            vm.dismissBackupHint()
            advanceUntilIdle()

            assertNull(vm.state.value.backupHint, "点 × 之后要立即消失")
            assertTrue(settings.value.backupHintDismissedAt > 0, "关闭时间要落盘，杀进程重进也不该再弹")
        }

    private companion object {
        /** 一天的毫秒数，测试里算「几天前」用。 */
        const val DAY = 24 * 60 * 60 * 1000L
    }
}
