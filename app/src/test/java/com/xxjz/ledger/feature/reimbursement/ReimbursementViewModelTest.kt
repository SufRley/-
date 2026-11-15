package com.xxjz.ledger.feature.reimbursement

import com.xxjz.ledger.core.common.DateUtils
import com.xxjz.ledger.domain.model.Account
import com.xxjz.ledger.domain.model.AccountType
import com.xxjz.ledger.domain.model.Category
import com.xxjz.ledger.domain.model.Txn
import com.xxjz.ledger.domain.model.TxnType
import com.xxjz.ledger.domain.usecase.ReimburseTxnUseCase
import com.xxjz.ledger.testutil.FakeAccountRepository
import com.xxjz.ledger.testutil.FakeCategoryRepository
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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

/** M7 待报销页：合计、确认、撤销与提示文案（AC-59 ~ AC-62）。 */
@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("待报销页")
class ReimbursementViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val txnRepo = FakeTxnRepository()
    private val categoryRepo = FakeCategoryRepository()
    private val accountRepo = FakeAccountRepository()

    private val today: LocalDate = DateUtils.today()
    private val now: Long = DateUtils.toMillis(today, LocalTime.NOON)

    private fun viewModel() =
        ReimbursementViewModel(
            txnRepo,
            categoryRepo,
            ReimburseTxnUseCase(txnRepo, categoryRepo, accountRepo),
            accountRepo,
        )

    private fun pending(
        id: String,
        amount: Long,
        note: String,
    ) {
        txnRepo.items[id] =
            Txn(
                id = id,
                type = TxnType.EXPENSE,
                amount = amount,
                categoryId = "exp_1_0",
                accountId = "acc-1",
                dateTime = now,
                dateKey = DateUtils.dateKeyOf(today),
                note = note,
                pendingReimbursement = true,
                createdAt = now,
                updatedAt = now,
            )
    }

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        categoryRepo.categories = listOf(Category(id = "exp_1_0", name = "打车", type = TxnType.EXPENSE))
    }

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    @Test
    @DisplayName("待报销列表与合计")
    fun listsPending() =
        runTest {
            pending("t1", 12_000, "打车去机场")
            pending("t2", 8_000, "高铁")

            val vm = viewModel()
            advanceUntilIdle()

            assertEquals(2, vm.state.value.pending.size)
            assertEquals(20_000, vm.state.value.pendingTotal)
            assertEquals("打车", vm.state.value.pending.first().categoryName)
            assertTrue(vm.state.value.pending.first().dateLabel.isNotBlank())
        }

    @Test
    @DisplayName("部分报销：提示自付差额，列表转入已报销")
    fun confirmPartial() =
        runTest {
            pending("t1", 12_000, "打车去机场")
            val vm = viewModel()
            advanceUntilIdle()

            vm.confirm("t1", "90")
            advanceUntilIdle()

            assertEquals("已报销 ¥90.00，自付 ¥30.00", vm.state.value.message)
            assertTrue(vm.state.value.pending.isEmpty())
            assertEquals(1, vm.state.value.done.size)
            assertEquals(9_000, vm.state.value.done.single().txn.reimbursedAmount)
        }

    @Test
    @DisplayName("全额报销提示不同文案")
    fun confirmFull() =
        runTest {
            pending("t1", 12_000, "打车去机场")
            val vm = viewModel()
            advanceUntilIdle()

            vm.confirm("t1", "120")
            advanceUntilIdle()

            assertEquals("已全额报销 ¥120.00", vm.state.value.message)
        }

    @Test
    @DisplayName("非法金额不改变数据只提示")
    fun invalidAmount() =
        runTest {
            pending("t1", 12_000, "打车去机场")
            val vm = viewModel()
            advanceUntilIdle()

            vm.confirm("t1", "abc")
            advanceUntilIdle()
            assertEquals("请输入有效的报销金额", vm.state.value.message)
            assertEquals(1, vm.state.value.pending.size)

            vm.consumeMessage()
            vm.confirm("t1", "200")
            advanceUntilIdle()
            assertEquals("报销金额不能超过支出金额", vm.state.value.message)
        }

    @Test
    @DisplayName("撤销报销后回到待报销")
    fun undo() =
        runTest {
            pending("t1", 12_000, "打车去机场")
            val vm = viewModel()
            advanceUntilIdle()

            vm.confirm("t1", "120")
            advanceUntilIdle()
            vm.undo("t1")
            advanceUntilIdle()

            assertEquals("已撤销报销", vm.state.value.message)
            assertEquals(1, vm.state.value.pending.size)
            assertTrue(vm.state.value.done.isEmpty())
            assertTrue(txnRepo.items.getValue("t1").pendingReimbursement)
        }

    @Test
    @DisplayName("消费提示后置空")
    fun consumeMessage() =
        runTest {
            pending("t1", 1_000, "打车")
            val vm = viewModel()
            advanceUntilIdle()
            vm.confirm("t1", "0")
            advanceUntilIdle()
            vm.consumeMessage()
            assertNull(vm.state.value.message)
        }

    // ---- v1.9.4 第 2 条：到账账户列表 ----

    private fun account(
        id: String,
        type: AccountType,
        hidden: Boolean = false,
        sortOrder: Int = 0,
    ): Account {
        val a = Account(id = id, name = id, type = type, hidden = hidden, sortOrder = sortOrder)
        accountRepo.items[id] = a
        return a
    }

    @Test
    @DisplayName("到账账户只列现金 / 储蓄卡 / 投资，且不含隐藏账户")
    fun fundingAccountsFiltered() =
        runTest(dispatcher.scheduler) {
            account("cash", AccountType.CASH, sortOrder = 0)
            account("savings", AccountType.SAVINGS, sortOrder = 1)
            account("invest", AccountType.INVEST, sortOrder = 2)
            account("credit", AccountType.CREDIT, sortOrder = 3)
            account("debt", AccountType.DEBT, sortOrder = 4)
            account("ghost", AccountType.SAVINGS, hidden = true, sortOrder = 5)

            val vm = viewModel()
            advanceUntilIdle()

            assertEquals(listOf("cash", "savings", "invest"), vm.state.value.fundingAccounts.map { it.id })
        }

    @Test
    @DisplayName("到账账户按 sortOrder 排，与账户页顺序一致")
    fun fundingAccountsSorted() =
        runTest(dispatcher.scheduler) {
            account("b", AccountType.SAVINGS, sortOrder = 2)
            account("a", AccountType.CASH, sortOrder = 1)
            val vm = viewModel()
            advanceUntilIdle()
            assertEquals(listOf("a", "b"), vm.state.value.fundingAccounts.map { it.id })
        }

    @Test
    @DisplayName("确认报销时把到账账户与时间透传给用例")
    fun confirmPassesAccountAndTime() =
        runTest(dispatcher.scheduler) {
            pending("t1", 12_000, "打车")
            account("cash", AccountType.CASH)
            val vm = viewModel()
            advanceUntilIdle()
            val chosen = DateUtils.toMillis(today.minusDays(1), LocalTime.of(9, 30))

            vm.confirm("t1", "120", accountId = "cash", dateTime = chosen)
            advanceUntilIdle()

            val income = txnRepo.items.values.first { it.type == TxnType.INCOME }
            assertEquals("cash", income.accountId)
            assertEquals(chosen, income.dateTime)
            assertEquals(DateUtils.dateKeyOf(today.minusDays(1)), income.dateKey)
        }

    @Test
    @DisplayName("撤销仍正常：删掉收入，原账回到待报销")
    fun undoStillWorks() =
        runTest(dispatcher.scheduler) {
            pending("t1", 12_000, "打车")
            account("cash", AccountType.CASH)
            val vm = viewModel()
            advanceUntilIdle()
            vm.confirm("t1", "120", accountId = "cash")
            advanceUntilIdle()

            vm.undo("t1")
            advanceUntilIdle()

            assertTrue(txnRepo.items.values.none { it.type == TxnType.INCOME })
            assertTrue(txnRepo.items.getValue("t1").pendingReimbursement)
        }

    // ---- v1.9.4 第 5 条：待报销按日期分组 ----

    private fun pendingAt(
        id: String,
        amount: Long,
        date: LocalDate,
        time: LocalTime = LocalTime.NOON,
    ) {
        val millis = DateUtils.toMillis(date, time)
        txnRepo.items[id] =
            Txn(
                id = id,
                type = TxnType.EXPENSE,
                amount = amount,
                categoryId = "exp_1_0",
                accountId = "acc-1",
                dateTime = millis,
                dateKey = DateUtils.dateKeyOf(date),
                note = "note-$id",
                pendingReimbursement = true,
                createdAt = millis,
                updatedAt = millis,
            )
    }

    private fun rowsOf(vararg ids: String): List<ReimbursementRow> =
        ids.map { id ->
            ReimbursementRow(
                txn = txnRepo.items.getValue(id),
                categoryName = "餐饮",
                dateLabel = DateUtils.friendlyDate(DateUtils.toDate(txnRepo.items.getValue(id).dateTime)),
            )
        }

    @Test
    @DisplayName("按 dateKey 分组，日期倒序，最近的一天在最上")
    fun groupsSortedByDateDesc() =
        runTest {
            pendingAt("a", 1_000, today.minusDays(2))
            pendingAt("b", 2_000, today)
            pendingAt("c", 3_000, today.minusDays(1))

            val groups = pendingGroupsOf(rowsOf("a", "b", "c"))

            assertEquals(
                listOf(DateUtils.dateKeyOf(today), DateUtils.dateKeyOf(today.minusDays(1)), DateUtils.dateKeyOf(today.minusDays(2))),
                groups.map { it.dateKey },
            )
        }

    @Test
    @DisplayName("组内按时间倒序，同一时刻按 createdAt 倒序")
    fun rowsInsideGroupSortedByTimeDesc() =
        runTest {
            pendingAt("morning", 1_000, today, LocalTime.of(8, 0))
            pendingAt("evening", 2_000, today, LocalTime.of(20, 0))
            pendingAt("noon", 3_000, today, LocalTime.NOON)

            val group = pendingGroupsOf(rowsOf("morning", "evening", "noon")).single()

            assertEquals(listOf("evening", "noon", "morning"), group.rows.map { it.txn.id })
        }

    @Test
    @DisplayName("组头给出该组笔数与合计")
    fun groupHeaderCountsAndTotal() =
        runTest {
            pendingAt("a", 1_000, today)
            pendingAt("b", 2_500, today)
            pendingAt("c", 700, today.minusDays(1))

            val groups = pendingGroupsOf(rowsOf("a", "b", "c"))

            assertEquals(2, groups.first().rows.size)
            assertEquals(3_500L, groups.first().total)
            assertEquals(1, groups.last().rows.size)
            assertEquals(700L, groups.last().total)
        }

    @Test
    @DisplayName("组头日期文案用「今天 / 昨天 / M月d日」")
    fun groupLabelUsesFriendlyDate() =
        runTest {
            pendingAt("a", 1_000, today)
            pendingAt("b", 1_000, today.minusDays(1))

            val labels = pendingGroupsOf(rowsOf("a", "b")).map { it.dateLabel }

            assertEquals(listOf("今天", "昨天"), labels)
        }

    @Test
    @DisplayName("同一天拆成同一组，不会因为跨年的同名文案被合并")
    fun sameDaySingleGroup() =
        runTest {
            pendingAt("a", 1_000, today, LocalTime.of(9, 0))
            pendingAt("b", 1_000, today, LocalTime.of(21, 0))

            assertEquals(1, pendingGroupsOf(rowsOf("a", "b")).size)
        }

    @Test
    @DisplayName("空列表不产生组头")
    fun emptyListNoGroups() =
        runTest {
            assertTrue(pendingGroupsOf(emptyList()).isEmpty())
        }

    @Test
    @DisplayName("状态里仍带着全部待报销笔数，顶部汇总不被折叠影响")
    fun totalCountUnaffectedByGrouping() =
        runTest(dispatcher.scheduler) {
            pendingAt("a", 1_000, today)
            pendingAt("b", 2_000, today.minusDays(1))
            pendingAt("c", 3_000, today.minusDays(2))
            val vm = viewModel()
            advanceUntilIdle()

            assertEquals(3, vm.state.value.pending.size)
            assertEquals(6_000L, vm.state.value.pendingTotal)
            assertEquals(3, pendingGroupsOf(vm.state.value.pending).size)
        }
}
