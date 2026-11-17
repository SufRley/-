package com.xxjz.ledger.feature.ledger

import com.xxjz.ledger.domain.model.Account
import com.xxjz.ledger.domain.model.AccountType
import com.xxjz.ledger.domain.model.Category
import com.xxjz.ledger.domain.model.Txn
import com.xxjz.ledger.domain.model.TxnType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** M2 明细分组测试：按天分组、日小计口径、排序（AC-26 / AC-12）。 */
@DisplayName("明细按天分组")
class LedgerGrouperTest {
    private val today = LocalDate.of(2025, 11, 13)

    private val categories =
        mapOf(
            "food" to Category(id = "food", name = "餐饮", type = TxnType.EXPENSE),
            "salary" to Category(id = "salary", name = "工资", type = TxnType.INCOME),
        )

    private fun txn(
        id: String,
        type: TxnType,
        amount: Long,
        date: LocalDate,
        categoryId: String? = "food",
        excluded: Boolean = false,
        deleted: Boolean = false,
        minute: Int = 12,
    ): Txn {
        val millis = date.atTime(minute, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        return Txn(
            id = id,
            type = type,
            amount = amount,
            categoryId = categoryId,
            accountId = "acc",
            dateTime = millis,
            dateKey = date.year * 10000 + date.monthValue * 100 + date.dayOfMonth,
            excludedFromStats = excluded,
            deletedAt = if (deleted) 1L else null,
            createdAt = millis,
            updatedAt = millis,
        )
    }

    @Test
    fun `同一天聚成一组并按日期倒序`() {
        val list =
            listOf(
                txn("a", TxnType.EXPENSE, 1000, today),
                txn("b", TxnType.EXPENSE, 2000, today.minusDays(1)),
                txn("c", TxnType.EXPENSE, 500, today, minute = 9),
            )
        val groups = LedgerGrouper.group(list, categories, today)
        assertEquals(2, groups.size)
        assertEquals(2, groups[0].rows.size)
        assertEquals(1500L, groups[0].expense)
        assertEquals("今天", groups[0].title)
        assertEquals("昨天", groups[1].title)
    }

    @Test
    fun `不计入收支的账目仍展示但不进日小计`() {
        val list =
            listOf(
                txn("a", TxnType.EXPENSE, 1000, today),
                txn("b", TxnType.EXPENSE, 9999, today, excluded = true),
            )
        val group = LedgerGrouper.group(list, categories, today).first()
        assertEquals(2, group.rows.size)
        assertEquals(1000L, group.expense)
    }

    @Test
    fun `转账不计入日小计`() {
        val list =
            listOf(
                txn("a", TxnType.TRANSFER, 8888, today, categoryId = null),
                txn("b", TxnType.INCOME, 5000, today, categoryId = "salary"),
            )
        val group = LedgerGrouper.group(list, categories, today).first()
        assertEquals(0L, group.expense)
        assertEquals(5000L, group.income)
        assertEquals("转账", group.rows.first { it.txn.id == "a" }.categoryName)
    }

    @Test
    fun `软删除的记录不出现`() {
        val list =
            listOf(
                txn("a", TxnType.EXPENSE, 1000, today, deleted = true),
                txn("b", TxnType.EXPENSE, 500, today),
            )
        val groups = LedgerGrouper.group(list, categories, today)
        assertEquals(1, groups.size)
        assertEquals(1, groups[0].rows.size)
        assertFalse(groups[0].rows.any { it.txn.id == "a" })
    }

    @Test
    fun `组内按时间倒序`() {
        val list =
            listOf(
                txn("early", TxnType.EXPENSE, 100, today, minute = 8),
                txn("late", TxnType.EXPENSE, 100, today, minute = 20),
            )
        val rows = LedgerGrouper.group(list, categories, today).first().rows
        assertEquals("late", rows[0].txn.id)
        assertEquals("early", rows[1].txn.id)
    }

    @Test
    fun `未知分类回退为未分类`() {
        val list = listOf(txn("a", TxnType.EXPENSE, 100, today, categoryId = "missing"))
        val row = LedgerGrouper.group(list, categories, today).first().rows.first()
        assertEquals("未分类", row.categoryName)
        assertTrue(row.categoryIcon.isEmpty())
    }

    // ---------- 行内展示信息（v1.9.5 第 4 条） ----------

    private val accounts =
        mapOf(
            "acc" to Account(id = "acc", name = "招行储蓄卡", type = AccountType.SAVINGS),
            "wechat" to Account(id = "wechat", name = "微信", type = AccountType.CASH),
        )

    @Test
    fun `行内带出账户名与时间`() {
        val row =
            LedgerGrouper
                .group(listOf(txn("a", TxnType.EXPENSE, 1000, today, minute = 9)), categories, today, accounts)
                .first()
                .rows
                .first()

        assertEquals("招行储蓄卡", row.accountName)
        assertEquals("09:00", row.timeLabel)
    }

    @Test
    fun `转账行带出两端账户名`() {
        val t =
            txn("t", TxnType.TRANSFER, 5000, today).copy(toAccountId = "wechat")
        val row = LedgerGrouper.group(listOf(t), categories, today, accounts).first().rows.first()

        assertEquals("招行储蓄卡", row.accountName)
        assertEquals("微信", row.toAccountName)
    }

    @Test
    fun `非转账行不吐转入账户`() {
        val row =
            LedgerGrouper
                .group(listOf(txn("a", TxnType.EXPENSE, 1000, today)), categories, today, accounts)
                .first()
                .rows
                .first()

        assertNull(row.toAccountName)
    }

    @Test
    fun `账户被删掉时账户名为空串而不是抛异常`() {
        val t = txn("a", TxnType.EXPENSE, 1000, today).copy(accountId = "gone")
        val row = LedgerGrouper.group(listOf(t), categories, today, accounts).first().rows.first()

        assertEquals("", row.accountName)
    }

    @Test
    fun `不传账户表时行内字段退化为空，不崩`() {
        val row =
            LedgerGrouper
                .group(listOf(txn("a", TxnType.EXPENSE, 1000, today)), categories, today)
                .first()
                .rows
                .first()

        assertEquals("", row.accountName)
        assertEquals("12:00", row.timeLabel, "时间不依赖账户表")
    }

    @Test
    fun `行内日期补零到 MM-dd`() {
        val t = txn("a", TxnType.EXPENSE, 1000, LocalDate.of(2026, 3, 7))
        val row = LedgerGrouper.group(listOf(t), categories, today).first().rows.first()

        assertEquals("03-07", row.dateLabel, "个位数月份与日期都要补零，否则一列日期长短不齐")
    }

    @Test
    fun `日期与时间取自交易本身，不取当天`() {
        val t = txn("a", TxnType.EXPENSE, 1000, LocalDate.of(2026, 11, 1))
        val row = LedgerGrouper.group(listOf(t), categories, LocalDate.of(2026, 11, 16)).first().rows.first()

        assertEquals("11-01", row.dateLabel)
        assertEquals("12:00", row.timeLabel)
    }
}
