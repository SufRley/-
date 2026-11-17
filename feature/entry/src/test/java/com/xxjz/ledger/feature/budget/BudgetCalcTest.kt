package com.xxjz.ledger.feature.budget

import com.xxjz.ledger.core.common.DateUtils
import com.xxjz.ledger.domain.model.Budget
import com.xxjz.ledger.domain.model.BudgetPeriod
import com.xxjz.ledger.domain.model.BudgetScope
import com.xxjz.ledger.domain.model.Category
import com.xxjz.ledger.domain.model.Txn
import com.xxjz.ledger.domain.model.TxnType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

/** M6 预算计算：范围展开、已用口径、进度与文案（AC-44 ~ AC-47、AC-51）。 */
@DisplayName("预算计算")
class BudgetCalcTest {
    private val categories =
        listOf(
            Category(id = "food", name = "餐饮", type = TxnType.EXPENSE),
            Category(id = "breakfast", name = "早餐", parentId = "food", type = TxnType.EXPENSE),
            Category(id = "lunch", name = "午餐", parentId = "food", type = TxnType.EXPENSE),
            Category(id = "traffic", name = "交通", type = TxnType.EXPENSE),
            Category(id = "salary", name = "工资", type = TxnType.INCOME),
        )

    private val today = LocalDate.of(2026, 11, 13)
    private val monthKey = 202611

    private fun txn(
        id: String,
        amount: Long,
        categoryId: String?,
        date: LocalDate = LocalDate.of(2026, 11, 5),
        type: TxnType = TxnType.EXPENSE,
        excluded: Boolean = false,
        deletedAt: Long? = null,
    ): Txn {
        val millis = DateUtils.toMillis(date, LocalTime.NOON)
        return Txn(
            id = id,
            type = type,
            amount = amount,
            categoryId = categoryId,
            accountId = "acc",
            dateTime = millis,
            dateKey = DateUtils.dateKeyOf(date),
            excludedFromStats = excluded,
            deletedAt = deletedAt,
            createdAt = millis,
            updatedAt = millis,
        )
    }

    private fun budget(
        amount: Long,
        scope: BudgetScope = BudgetScope.TOTAL,
        categoryIds: List<String> = emptyList(),
        period: BudgetPeriod = BudgetPeriod.MONTH,
        enabled: Boolean = true,
    ) = Budget(
        id = "b1",
        monthKey = monthKey,
        name = "预算",
        period = period,
        scope = scope,
        categoryIds = categoryIds,
        amount = amount,
        enabled = enabled,
    )

    @Test
    @DisplayName("选大类会展开到所有子类，选小类不会反向带上大类")
    fun expandCategories() {
        val byParent = BudgetCalc.expandCategories(listOf("food"), categories)
        assertEquals(setOf("food", "breakfast", "lunch"), byParent)

        val byChild = BudgetCalc.expandCategories(listOf("lunch"), categories)
        assertEquals(setOf("lunch"), byChild)

        assertTrue(BudgetCalc.expandCategories(emptyList(), categories).isEmpty())
    }

    @Test
    @DisplayName("总预算只统计本月支出，排除软删、不计入收支与非支出")
    fun usedOfTotalScope() {
        val txns =
            listOf(
                txn("t1", 1000, "lunch"),
                txn("t2", 2000, "traffic"),
                txn("t3", 3000, "lunch", excluded = true),
                txn("t4", 4000, "lunch", deletedAt = 1L),
                txn("t5", 5000, "salary", type = TxnType.INCOME),
                txn("t6", 6000, "lunch", date = LocalDate.of(2026, 10, 31)),
            )
        assertEquals(3000L, BudgetCalc.usedOf(budget(100_000), txns, categories, monthKey))
    }

    @Test
    @DisplayName("分类预算把大类下的小类一起算进已用")
    fun usedOfCategoryScope() {
        val txns =
            listOf(
                txn("t1", 1000, "breakfast"),
                txn("t2", 2000, "lunch"),
                txn("t3", 4000, "traffic"),
            )
        val b = budget(amount = 50_000, scope = BudgetScope.CATEGORY, categoryIds = listOf("food"))
        assertEquals(3000L, BudgetCalc.usedOf(b, txns, categories, monthKey))
    }

    @Test
    @DisplayName("按年的预算统计整年")
    fun usedOfYearPeriod() {
        val txns =
            listOf(
                txn("t1", 1000, "lunch", date = LocalDate.of(2026, 3, 1)),
                txn("t2", 2000, "lunch", date = LocalDate.of(2026, 12, 31)),
                txn("t3", 4000, "lunch", date = LocalDate.of(2025, 12, 31)),
            )
        val b = budget(amount = 100_000, period = BudgetPeriod.YEAR)
        assertEquals(3000L, BudgetCalc.usedOf(b, txns, categories, monthKey))
    }

    @Test
    @DisplayName("剩余天数：当月含今天，历史月为 0，未来月为整月")
    fun remainingDays() {
        val b = budget(100)
        assertEquals(18, BudgetCalc.remainingDays(b, 202611, today)) // 11/13..11/30
        assertEquals(0, BudgetCalc.remainingDays(b, 202610, today))
        assertEquals(31, BudgetCalc.remainingDays(b, 202612, today))
    }

    @Test
    @DisplayName("日均可花 = 剩余额度 / 剩余天数")
    fun dailyAvailable() {
        val p = BudgetCalc.progress(budget(10_000), used = 1000, monthKey = monthKey, today = today)
        assertEquals(9000L, p.remaining)
        assertNotNull(p.dailyAvailable)
        assertEquals(500L, p.dailyAvailable) // 9000 / 18
        assertEquals(10, p.percent)
        assertFalse(p.overspent)
    }

    @Test
    @DisplayName("超支时给出超支金额，日均可花为空，进度条钳在满格")
    fun overspent() {
        val p = BudgetCalc.progress(budget(1000), used = 1500, monthKey = monthKey, today = today)
        assertTrue(p.overspent)
        assertEquals(500L, p.over)
        assertEquals(-500L, p.remaining)
        assertEquals(150, p.percent)
        assertEquals(1f, p.barFraction)
        assertNull(p.dailyAvailable)
        assertEquals("已超支 ¥5.00", remainingText(p))
    }

    @Test
    @DisplayName("用量到 80% 进入预警，额度用尽显示已用完")
    fun warningAndExhausted() {
        val warning = BudgetCalc.progress(budget(1000), used = 800, monthKey = monthKey, today = today)
        assertEquals(BudgetStatus.WARNING, warning.status)

        val exhausted = BudgetCalc.progress(budget(1000), used = 1000, monthKey = monthKey, today = today)
        assertEquals("已用完", remainingText(exhausted))
        assertNull(exhausted.dailyAvailable)
        assertEquals("预算已用完", dailyText(exhausted))
    }

    @Test
    @DisplayName("停用的预算状态为 DISABLED 且不计入汇总")
    fun disabledExcludedFromSummary() {
        val p = BudgetCalc.progress(budget(1000, enabled = false), used = 100, monthKey = monthKey, today = today)
        assertEquals(BudgetStatus.DISABLED, p.status)
        assertEquals("已停用", dailyText(p))
        val (total, used) = BudgetCalc.summarize(listOf(p))
        assertEquals(0L, total)
        assertEquals(0L, used)
    }

    @Test
    @DisplayName("排序：超支置顶，其次按使用比例，停用排最后")
    fun sortOrder() {
        val over =
            BudgetCalc.progress(
                budget(1000).copy(id = "over"),
                used = 2000,
                monthKey = monthKey,
                today = today,
            )
        val high =
            BudgetCalc.progress(
                budget(1000).copy(id = "high"),
                used = 900,
                monthKey = monthKey,
                today = today,
            )
        val low =
            BudgetCalc.progress(
                budget(1000).copy(id = "low"),
                used = 100,
                monthKey = monthKey,
                today = today,
            )
        val off =
            BudgetCalc.progress(
                budget(1000).copy(id = "off", enabled = false),
                used = 0,
                monthKey = monthKey,
                today = today,
            )
        val sorted = BudgetCalc.sort(listOf(low, off, over, high)).map { it.budget.id }
        assertEquals(listOf("over", "high", "low", "off"), sorted)
    }

    @Test
    @DisplayName("总额为 0 的预算标记为 EMPTY，不产生除零")
    fun emptyAmount() {
        val p = BudgetCalc.progress(budget(0), used = 0, monthKey = monthKey, today = today)
        assertEquals(BudgetStatus.EMPTY, p.status)
        assertEquals(0, p.percent)
        assertEquals(0f, p.barFraction)
    }
}
