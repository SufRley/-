package com.xxjz.ledger.feature.export

import com.xxjz.ledger.core.common.DateUtils
import com.xxjz.ledger.domain.model.Account
import com.xxjz.ledger.domain.model.AccountType
import com.xxjz.ledger.domain.model.Category
import com.xxjz.ledger.domain.model.Txn
import com.xxjz.ledger.domain.model.TxnType
import com.xxjz.ledger.testutil.FakeAccountRepository
import com.xxjz.ledger.testutil.FakeCategoryRepository
import com.xxjz.ledger.testutil.FakeDocumentIo
import com.xxjz.ledger.testutil.FakeTxnRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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

/** M8 CSV 导出（AC-75）：范围、字段勾选、实际写出的内容。 */
@OptIn(ExperimentalCoroutinesApi::class)
class CsvExportViewModelTest {
    private val txnRepo = FakeTxnRepository()
    private val categoryRepo = FakeCategoryRepository()
    private val accountRepo = FakeAccountRepository()
    private val io = FakeDocumentIo()

    private lateinit var viewModel: CsvExportViewModel

    private val today: LocalDate = DateUtils.today()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        categoryRepo.categories =
            listOf(
                Category(id = "food", name = "餐饮", type = TxnType.EXPENSE),
                Category(id = "lunch", name = "午餐", parentId = "food", type = TxnType.EXPENSE),
            )
        accountRepo.items["a1"] = Account(id = "a1", name = "现金", type = AccountType.FUND)
        viewModel = CsvExportViewModel(txnRepo, categoryRepo, accountRepo, io)
        viewModel.ioDispatcher = UnconfinedTestDispatcher()
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun txn(
        id: String,
        date: LocalDate,
        amount: Long = 1_000,
        note: String = "",
        type: TxnType = TxnType.EXPENSE,
        deletedAt: Long? = null,
    ) = Txn(
        id = id,
        type = type,
        amount = amount,
        categoryId = "lunch",
        accountId = "a1",
        dateTime = DateUtils.toMillis(date, java.time.LocalTime.NOON),
        dateKey = date.year * 10000 + date.monthValue * 100 + date.dayOfMonth,
        note = note,
        deletedAt = deletedAt,
        createdAt = 1_700_000_000_000,
        updatedAt = 1_700_000_000_000,
    )

    @Test
    @DisplayName("默认范围是本月，条数只算本月的未删除记录")
    fun defaultCount() =
        runTest {
            txnRepo.put(txn("t1", today))
            txnRepo.put(txn("t2", today.withDayOfMonth(1)))
            txnRepo.put(txn("old", LocalDate.of(2020, 1, 1)))
            txnRepo.put(txn("gone", today, deletedAt = 1L))
            // 条数是 ViewModel 初始化那一刻拍的快照，数据必须先备好再让它算
            viewModel.refreshCountForTest()

            assertEquals(CsvRange.MONTH, viewModel.state.value.range)
            assertEquals(2, viewModel.state.value.previewCount)
        }

    @Test
    @DisplayName("切到「全部」后条数变大")
    fun allRange() =
        runTest {
            txnRepo.put(txn("t1", today))
            txnRepo.put(txn("old", LocalDate.of(2020, 1, 1)))
            viewModel.selectRange(CsvRange.ALL)
            assertEquals(2, viewModel.state.value.previewCount)
        }

    @Test
    @DisplayName("导出写入的内容带 BOM、表头与业务数据")
    fun exportContent() =
        runTest {
            txnRepo.put(txn("t1", today, amount = 12_345, note = "午饭,加饮料"))

            viewModel.exportTo("content://test/out.csv")
            advanceUntilIdle()

            val text = String(io.files.getValue("content://test/out.csv"), Charsets.UTF_8)
            assertTrue(text.startsWith("\uFEFF"))
            assertTrue(text.contains("日期,时间,类型,分类,账户,金额,备注"))
            assertTrue(text.contains("支出"))
            assertTrue(text.contains("餐饮/午餐"))
            assertTrue(text.contains("现金"))
            assertTrue(text.contains("123.45"))
            assertTrue(text.contains("\"午饭,加饮料\""))
        }

    @Test
    @DisplayName("取消勾选字段后导出内容里就没有这一列")
    fun toggleField() =
        runTest {
            txnRepo.put(txn("t1", today))
            viewModel.toggleField(CsvField.NOTE)
            viewModel.exportTo("content://test/out.csv")
            advanceUntilIdle()

            val header = String(io.files.getValue("content://test/out.csv"), Charsets.UTF_8).split("\r\n").first()
            assertFalse(header.contains("备注"))
        }

    @Test
    @DisplayName("一个字段都不选时拒绝导出并提示")
    fun noFields() =
        runTest {
            CsvExporter.DEFAULT_FIELDS.forEach { viewModel.toggleField(it) }
            assertFalse(viewModel.state.value.canExport)
            viewModel.exportTo("content://test/out.csv")
            assertEquals("至少勾选一个字段", viewModel.state.value.message)
            assertTrue(io.files.isEmpty())
        }

    @Test
    @DisplayName("写入失败给出提示，不抛异常")
    fun writeFails() =
        runTest {
            txnRepo.put(txn("t1", today))
            io.failOnWrite = true
            viewModel.exportTo("content://test/out.csv")
            assertTrue(viewModel.state.value.message!!.contains("导出失败"))
            assertFalse(viewModel.state.value.busy)
        }

    @Test
    @DisplayName("自定义区间只导出区间内的记录")
    fun customRange() =
        runTest {
            txnRepo.put(txn("in", LocalDate.of(2026, 9, 10)))
            txnRepo.put(txn("out", LocalDate.of(2026, 9, 25)))

            viewModel.setCustomRange(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 15))
            assertEquals(1, viewModel.state.value.previewCount)

            viewModel.exportTo("content://test/out.csv")
            advanceUntilIdle()

            val text = String(io.files.getValue("content://test/out.csv"), Charsets.UTF_8)
            assertTrue(text.contains("2026-09-10"))
            assertFalse(text.contains("2026-09-25"))
        }
}
