package com.xxjz.ledger.domain.usecase

import com.xxjz.ledger.core.common.AppError
import com.xxjz.ledger.core.common.AppResult
import com.xxjz.ledger.domain.model.Budget
import com.xxjz.ledger.domain.model.BudgetScope
import com.xxjz.ledger.testutil.FakeBudgetRepository
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** M6-T3 / M6-T6：预算保存校验与删除（AC-44、AC-50）。 */
@DisplayName("预算保存与删除")
class SaveBudgetUseCaseTest {
    private val repo = FakeBudgetRepository()
    private val month = GetBudgetForMonthUseCase(repo)
    private val save = SaveBudgetUseCase(repo, month)
    private val delete = DeleteBudgetUseCase(repo, month)

    @Test
    @DisplayName("金额必须为正")
    fun rejectsNonPositiveAmount() =
        runTest {
            val r = save(BudgetDraft(amountCents = 0), 202611)
            assertTrue(r is AppResult.Failure)
            assertEquals(AppError.AmountNotPositive.message, (r as AppResult.Failure).error.message)
        }

    @Test
    @DisplayName("超过 9 位上限拒绝")
    fun rejectsTooLarge() =
        runTest {
            val r = save(BudgetDraft(amountCents = 100_000_000_000L), 202611)
            assertTrue(r is AppResult.Failure)
            assertEquals(AppError.AmountTooLarge.message, (r as AppResult.Failure).error.message)
        }

    @Test
    @DisplayName("按类别范围但没选分类时拒绝")
    fun rejectsEmptyCategoryScope() =
        runTest {
            val r = save(BudgetDraft(amountCents = 100_00, scope = BudgetScope.CATEGORY), 202611)
            assertTrue(r is AppResult.Failure)
            assertEquals("请选择要纳入预算的分类", (r as AppResult.Failure).error.message)
        }

    @Test
    @DisplayName("名称留空按范围给默认名")
    fun defaultNameByScope() =
        runTest {
            save(BudgetDraft(amountCents = 100_00), 202611)
            save(BudgetDraft(amountCents = 100_00, scope = BudgetScope.CATEGORY, categoryIds = listOf("food")), 202611)

            val names = repo.monthRows(202611).map { it.name }
            assertTrue(names.contains("总预算"))
            assertTrue(names.contains("分类预算"))
        }

    @Test
    @DisplayName("在本月保存时自动物化上月配置，历史月份保持不变")
    fun materializesOnSave() =
        runTest {
            repo.put(Budget(id = "old", monthKey = 202610, name = "旧预算", amount = 100_000))

            save(BudgetDraft(amountCents = 200_00, name = "新预算"), 202611)

            assertEquals(1, repo.monthRows(202610).size)
            assertEquals(100_000L, repo.monthRows(202610).single().amount)
            assertEquals(2, repo.monthRows(202611).size)
        }

    @Test
    @DisplayName("编辑已有预算只改本月版本")
    fun editKeepsId() =
        runTest {
            repo.put(Budget(id = "b1", monthKey = 202611, name = "原", amount = 100_000))
            val r = save(BudgetDraft(id = "b1", amountCents = 300_00, name = "改后"), 202611)
            assertEquals("b1", (r as AppResult.Success).data)
            assertEquals(300_00L, repo.rows["b1" to 202611]?.amount)
        }

    @Test
    @DisplayName("删除进回收站，本月不再显示，上月记录不受影响")
    fun deleteGoesToTrash() =
        runTest {
            repo.put(Budget(id = "b1", monthKey = 202610, name = "预算", amount = 100_000))
            month.materialize(202611)

            delete("b1", 202611, now = 999L)

            assertTrue(repo.monthRows(202611).single().deletedAt == 999L)
            assertTrue(month(202611).isEmpty())
            assertEquals(null, repo.monthRows(202610).single().deletedAt)
        }
}
