package com.xxjz.ledger.feature.category

import com.xxjz.ledger.domain.model.Category
import com.xxjz.ledger.domain.model.Tag
import com.xxjz.ledger.domain.model.TxnType
import com.xxjz.ledger.testutil.FakeCategoryRepository
import com.xxjz.ledger.testutil.FakeMerchantRepository
import com.xxjz.ledger.testutil.FakeTagRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** M9-D 分类 / 标签 / 商家管理（AC-79）。 */
@OptIn(ExperimentalCoroutinesApi::class)
class CategoryManageViewModelTest {
    private val categoryRepo = FakeCategoryRepository()
    private val tagRepo = FakeTagRepository()
    private val merchantRepo = FakeMerchantRepository()
    private lateinit var viewModel: CategoryManageViewModel

    private fun cat(
        id: String,
        name: String,
        parentId: String? = null,
        type: TxnType = TxnType.EXPENSE,
        hidden: Boolean = false,
        sortOrder: Int = 0,
    ) = Category(id = id, name = name, parentId = parentId, type = type, hidden = hidden, sortOrder = sortOrder)

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        categoryRepo.categories =
            listOf(
                cat("food", "餐饮", sortOrder = 0),
                cat("lunch", "午餐", parentId = "food", sortOrder = 0),
                cat("dinner", "晚餐", parentId = "food", sortOrder = 1),
                cat("traffic", "交通", sortOrder = 1),
                cat("salary", "工资", type = TxnType.INCOME),
            )
        viewModel = CategoryManageViewModel(categoryRepo, tagRepo, merchantRepo)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun groupsByType() =
        runTest {
            advanceUntilIdle()
            // 默认只看支出：收入分类不该混进来
            assertEquals(listOf("food", "traffic"), viewModel.state.value.groups.map { it.parent.id })
            assertEquals(listOf("lunch", "dinner"), viewModel.state.value.groups.first().children.map { it.id })
        }

    @Test
    fun switchType() =
        runTest {
            advanceUntilIdle()
            viewModel.selectType(TxnType.INCOME)
            advanceUntilIdle()
            assertEquals(TxnType.INCOME, viewModel.state.value.type)
            assertEquals(listOf("salary"), viewModel.state.value.groups.map { it.parent.id })
        }

    @Test
    fun blankNameRejected() =
        runTest {
            viewModel.saveCategory(null, "   ", null)
            advanceUntilIdle()
            assertEquals("分类名称不能为空", viewModel.state.value.message)
            // 没有新建任何东西
            assertTrue(categoryRepo.categories.none { it.name.isBlank() })
        }

    @Test
    fun duplicateNameRejected() =
        runTest {
            viewModel.saveCategory(null, " 餐饮 ", null)
            advanceUntilIdle()
            assertEquals("同层级下已经有「餐饮」了", viewModel.state.value.message)
            assertEquals(1, categoryRepo.categories.count { it.name == "餐饮" })
        }

    @Test
    fun sameNameDifferentParentAllowed() =
        runTest {
            advanceUntilIdle()
            // 「餐饮/午餐」和「交通/午餐」不冲突，不同层级
            viewModel.saveCategory(null, "午餐", "traffic")
            advanceUntilIdle()
            assertTrue(categoryRepo.categories.any { it.name == "午餐" && it.parentId == "traffic" })
            assertEquals("已新建「午餐」", viewModel.state.value.message)
        }

    @Test
    fun renameKeepsChildren() =
        runTest {
            advanceUntilIdle()
            viewModel.saveCategory("food", "吃饭", null)
            advanceUntilIdle()
            assertEquals("吃饭", categoryRepo.categories.first { it.id == "food" }.name)
            assertEquals(2, categoryRepo.categories.count { it.parentId == "food" })
        }

    @Test
    fun cannotNestParentUnderOther() =
        runTest {
            advanceUntilIdle()
            viewModel.saveCategory("food", "餐饮", "traffic")
            advanceUntilIdle()
            assertEquals("该分类下还有子分类，不能再设成子分类", viewModel.state.value.message)
            assertNull(categoryRepo.categories.first { it.id == "food" }.parentId)
        }

    @Test
    fun hideAndRestore() =
        runTest {
            advanceUntilIdle()
            viewModel.toggleHidden(cat("traffic", "交通"))
            advanceUntilIdle()
            assertTrue(categoryRepo.categories.first { it.id == "traffic" }.hidden)
            assertEquals("已隐藏", viewModel.state.value.message)

            viewModel.toggleHidden(cat("traffic", "交通", hidden = true))
            advanceUntilIdle()
            assertFalse(categoryRepo.categories.first { it.id == "traffic" }.hidden)
            assertEquals("已恢复显示", viewModel.state.value.message)
        }

    @Test
    fun deleteBlockedWhenTxnExists() =
        runTest {
            advanceUntilIdle()
            // 子类下有流水也算大类头上有流水
            categoryRepo.txnUsage["lunch"] = 3
            viewModel.requestDelete(cat("food", "餐饮"))
            advanceUntilIdle()
            assertEquals("「餐饮」下还有 3 笔账目，只能隐藏不能删除", viewModel.state.value.message)
            assertNull(viewModel.state.value.pendingDeleteCategoryId)
            assertTrue(categoryRepo.categories.any { it.id == "food" })
        }

    @Test
    fun deleteNeedsConfirm() =
        runTest {
            advanceUntilIdle()
            viewModel.requestDelete(cat("traffic", "交通"))
            advanceUntilIdle()
            // 只是弹确认，还没删
            assertEquals("traffic", viewModel.state.value.pendingDeleteCategoryId)
            assertTrue(categoryRepo.categories.any { it.id == "traffic" })

            viewModel.dismissDelete()
            assertNull(viewModel.state.value.pendingDeleteCategoryId)
            assertTrue(categoryRepo.categories.any { it.id == "traffic" })

            viewModel.requestDelete(cat("traffic", "交通"))
            advanceUntilIdle()
            viewModel.confirmDelete()
            advanceUntilIdle()
            assertTrue(categoryRepo.categories.none { it.id == "traffic" })
            assertEquals("已删除分类", viewModel.state.value.message)
        }

    @Test
    fun tagLifecycle() =
        runTest {
            advanceUntilIdle()
            viewModel.saveTag(null, "公司")
            advanceUntilIdle()
            assertEquals(listOf("公司"), viewModel.state.value.tags.map { it.name })

            viewModel.saveTag(null, "公司")
            advanceUntilIdle()
            assertEquals("已经有「公司」这个标签了", viewModel.state.value.message)
            assertEquals(1, viewModel.state.value.tags.size)

            viewModel.saveTag(null, "  ")
            advanceUntilIdle()
            assertEquals("标签名称不能为空", viewModel.state.value.message)

            val created = viewModel.state.value.tags.first()
            viewModel.deleteTag(created)
            advanceUntilIdle()
            assertTrue(viewModel.state.value.tags.isEmpty())
            assertEquals("已删除标签「公司」", viewModel.state.value.message)
        }

    @Test
    fun deletingTagDetachesFromTxn() =
        runTest {
            val tag = tagRepo.findOrCreate("旅行")
            tagRepo.setTxnTags("t1", listOf(tag.id))
            viewModel.deleteTag(tag)
            advanceUntilIdle()
            // 标签没了，流水身上的关联也一起断掉
            assertTrue(tagRepo.linksOf("t1").isEmpty())
        }

    @Test
    fun merchantLifecycle() =
        runTest {
            advanceUntilIdle()
            viewModel.saveMerchant(null, "全家")
            advanceUntilIdle()
            val created = viewModel.state.value.merchants.first()
            assertEquals("全家", created.name)

            viewModel.saveMerchant(created.id, "全家便利店")
            advanceUntilIdle()
            assertEquals("全家便利店", viewModel.state.value.merchants.first().name)

            viewModel.saveMerchant(null, "全家便利店")
            advanceUntilIdle()
            assertEquals("已经有「全家便利店」这个商家了", viewModel.state.value.message)

            viewModel.deleteMerchant(viewModel.state.value.merchants.first())
            advanceUntilIdle()
            assertTrue(viewModel.state.value.merchants.isEmpty())
        }

    @Test
    fun tabSwitchReloads() =
        runTest {
            advanceUntilIdle()
            tagRepo.upsert(Tag(id = "x", name = "标签A"))
            viewModel.selectTab(ManageTab.TAG)
            advanceUntilIdle()
            assertEquals(ManageTab.TAG, viewModel.state.value.tab)
            assertEquals(listOf("标签A"), viewModel.state.value.tags.map { it.name })
        }
}
