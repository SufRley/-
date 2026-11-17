package com.xxjz.ledger.domain.usecase

import com.xxjz.ledger.domain.model.Budget
import com.xxjz.ledger.domain.model.BudgetScope
import com.xxjz.ledger.testutil.FakeBudgetRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** M6-T5：按月取预算与「无则继承上月」（AC-49）。 */
@DisplayName("预算按月生效")
class GetBudgetForMonthUseCaseTest {
    private val repo = FakeBudgetRepository()
    private val useCase = GetBudgetForMonthUseCase(repo)

    private fun budget(
        id: String,
        monthKey: Int,
        amount: Long,
    ) = Budget(
        id = id,
        monthKey = monthKey,
        name = id,
        scope = BudgetScope.TOTAL,
        amount = amount,
    )

    @Test
    @DisplayName("本月没有配置时沿用最近一个有配置的月份，并标注来源")
    fun inheritsFromLatestConfiguredMonth() =
        runTest {
            repo.put(budget("b1", 202608, 100_000))
            repo.put(budget("b1", 202609, 80_000))
            repo.put(budget("b2", 202609, 120_000))

            val result = useCase(202611)
            assertEquals(2, result.size)
            assertTrue(result.all { it.inheritedFrom == 202609 })
            // 继承出来的行把 monthKey 归一到当前查看的月份，便于按本月统计
            assertTrue(result.all { it.budget.monthKey == 202611 })
            assertEquals(120_000L, result.first { it.budget.id == "b2" }.budget.amount)
        }

    @Test
    @DisplayName("本月自己有配置就用本月，不再继承")
    fun exactMonthWins() =
        runTest {
            repo.put(budget("b1", 202610, 100_000))
            repo.put(budget("b1", 202611, 300_000))

            val result = useCase(202611)
            assertEquals(1, result.size)
            assertNull(result.first().inheritedFrom)
            assertEquals(300_000L, result.first().budget.amount)
        }

    @Test
    @DisplayName("本月配置全被删掉也不会回退到上月")
    fun deletedMonthStaysEmpty() =
        runTest {
            repo.put(budget("b1", 202610, 100_000))
            repo.put(budget("b1", 202611, 100_000).copy(deletedAt = 1L))

            assertTrue(useCase(202611).isEmpty())
        }

    @Test
    @DisplayName("响应式取数：数据变化后自动重发")
    fun observeEmitsOnChange() =
        runTest {
            repo.put(budget("b1", 202610, 100_000))
            assertEquals(1, useCase.observe(202611).first().size)

            repo.put(budget("b2", 202610, 50_000))
            val latest = useCase.observe(202611).first()
            assertEquals(2, latest.size)
            assertNotNull(latest.first().inheritedFrom)
        }

    @Test
    @DisplayName("物化：把上月配置复制成本月版本，id 不变")
    fun materializeCopiesRows() =
        runTest {
            repo.put(budget("b1", 202610, 100_000))
            repo.put(budget("b2", 202610, 50_000))

            val rows = useCase.materialize(202611)
            assertEquals(2, rows.size)
            assertTrue(rows.all { it.monthKey == 202611 })
            // 上月原样保留，历史不受影响
            assertEquals(2, repo.monthRows(202610).size)
            assertEquals(2, repo.monthRows(202611).size)
            // 物化后不再是「继承」
            assertNull(useCase(202611).first().inheritedFrom)
        }

    @Test
    @DisplayName("已有配置时不重复复制")
    fun materializeIsIdempotent() =
        runTest {
            repo.put(budget("b1", 202611, 100_000))
            val rows = useCase.materialize(202611)
            assertEquals(1, rows.size)
            assertEquals(1, repo.rows.size)
        }
}
