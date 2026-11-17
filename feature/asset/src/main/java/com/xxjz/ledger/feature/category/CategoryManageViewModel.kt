package com.xxjz.ledger.feature.category

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xxjz.ledger.domain.model.Category
import com.xxjz.ledger.domain.model.Merchant
import com.xxjz.ledger.domain.model.Tag
import com.xxjz.ledger.domain.model.TxnType
import com.xxjz.ledger.domain.repository.CategoryRepository
import com.xxjz.ledger.domain.repository.MerchantRepository
import com.xxjz.ledger.domain.repository.TagRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 管理页三个分段（AC-79）。 */
enum class ManageTab(
    val label: String,
) {
    CATEGORY("分类"),
    TAG("标签"),
    MERCHANT("商家"),
}

/** 分类树节点，直接给 UI 的两级列表用。 */
data class CategoryGroup(
    val parent: Category,
    val children: List<Category>,
)

data class ManageState(
    val tab: ManageTab = ManageTab.CATEGORY,
    val type: TxnType = TxnType.EXPENSE,
    val groups: List<CategoryGroup> = emptyList(),
    val tags: List<Tag> = emptyList(),
    val merchants: List<Merchant> = emptyList(),
    /** 一次性的提示 / 错误文案。 */
    val message: String? = null,
    /** 等待用户确认删除的分类 id；非空时 UI 弹确认框。 */
    val pendingDeleteCategoryId: String? = null,
) {
    /** 当前类型下的大类数量，空态文案用。 */
    val majorCount: Int get() = groups.size
}

/**
 * 分类 / 标签 / 商家管理（AC-79）。
 *
 * 校验规则集中在这里，而不是散落在 UI 里：
 * - 名称不能为空、同类型下不能重名（大小写与首尾空格不敏感）；
 * - 大类本身不能再挂子类（本 App 固定两层）；
 * - 有流水的分类不允许删除，只能隐藏。
 */
@HiltViewModel
class CategoryManageViewModel
    @Inject
    constructor(
        private val categoryRepository: CategoryRepository,
        private val tagRepository: TagRepository,
        private val merchantRepository: MerchantRepository,
    ) : ViewModel() {
        private val _state = MutableStateFlow(ManageState())
        val state: StateFlow<ManageState> = _state.asStateFlow()

        init {
            reload()
        }

        fun selectTab(tab: ManageTab) {
            _state.update { it.copy(tab = tab) }
            reload()
        }

        fun selectType(type: TxnType) {
            _state.update { it.copy(type = type) }
            reload()
        }

        fun consumeMessage() = _state.update { it.copy(message = null) }

        fun dismissDelete() = _state.update { it.copy(pendingDeleteCategoryId = null) }

        private fun reload() {
            viewModelScope.launch {
                val snapshot = _state.value
                val all = categoryRepository.listAll().filter { it.type == snapshot.type }
                val childrenByParent = all.filter { it.parentId != null }.groupBy { it.parentId }
                _state.update {
                    it.copy(
                        groups =
                            all
                                .filter { c -> c.parentId == null }
                                .sortedBy { c -> c.sortOrder }
                                .map { parent -> CategoryGroup(parent, childrenByParent[parent.id].orEmpty()) },
                        tags = tagRepository.listAll(),
                        merchants = merchantRepository.listAll(),
                    )
                }
            }
        }

        // ---------------- 分类 ----------------

        /**
         * 新建或修改分类。
         *
         * @param id 为空表示新建
         * @param parentId 为空表示大类
         */
        fun saveCategory(
            id: String?,
            name: String,
            parentId: String?,
        ) {
            val trimmed = name.trim()
            val snapshot = _state.value
            val existing = snapshot.groups.flatMap { listOf(it.parent) + it.children }

            when {
                trimmed.isEmpty() -> return fail("分类名称不能为空")
                trimmed.length > MAX_NAME -> return fail("分类名称不能超过 $MAX_NAME 个字")
                existing.any { it.id != id && it.parentId == parentId && it.name.equals(trimmed, ignoreCase = true) } ->
                    return fail("同层级下已经有「$trimmed」了")
            }

            val target = existing.firstOrNull { it.id == id }
            if (target != null && parentId != null && hasChildren(snapshot.groups, target.id)) {
                return fail("该分类下还有子分类，不能再设成子分类")
            }

            viewModelScope.launch {
                categoryRepository.upsert(
                    Category(
                        id = id ?: com.xxjz.ledger.domain.model.Ids.newId("cat"),
                        name = trimmed,
                        parentId = parentId,
                        type = snapshot.type,
                        icon = target?.icon.orEmpty(),
                        color = target?.color.orEmpty(),
                        sortOrder = target?.sortOrder ?: (existing.size),
                        hidden = target?.hidden ?: false,
                        pinned = target?.pinned ?: false,
                        usageCount = target?.usageCount ?: 0,
                    ),
                )
                reload()
                _state.update { it.copy(message = if (id == null) "已新建「$trimmed」" else "已保存") }
            }
        }

        /** 隐藏 / 恢复；隐藏后不出现在记账页的分类选择里，但历史流水仍然正常显示。 */
        fun toggleHidden(category: Category) {
            viewModelScope.launch {
                categoryRepository.setHidden(category.id, !category.hidden)
                reload()
                _state.update { it.copy(message = if (category.hidden) "已恢复显示" else "已隐藏") }
            }
        }

        /** 先查流水条数：有流水的分类只能隐藏，不能删。 */
        fun requestDelete(category: Category) {
            viewModelScope.launch {
                val count = categoryRepository.txnCountOf(category.id)
                if (count > 0) {
                    _state.update { it.copy(message = "「${category.name}」下还有 $count 笔账目，只能隐藏不能删除") }
                } else {
                    _state.update { it.copy(pendingDeleteCategoryId = category.id) }
                }
            }
        }

        fun confirmDelete() {
            val id = _state.value.pendingDeleteCategoryId ?: return
            viewModelScope.launch {
                categoryRepository.delete(id)
                _state.update { it.copy(pendingDeleteCategoryId = null) }
                reload()
                _state.update { it.copy(message = "已删除分类") }
            }
        }

        // ---------------- 标签 ----------------

        fun saveTag(
            id: String?,
            name: String,
        ) {
            val trimmed = name.trim()
            if (trimmed.isEmpty()) return fail("标签名称不能为空")
            val existing = _state.value.tags
            if (existing.any { it.id != id && it.name.equals(trimmed, ignoreCase = true) }) {
                return fail("已经有「$trimmed」这个标签了")
            }
            viewModelScope.launch {
                val target = existing.firstOrNull { it.id == id }
                tagRepository.upsert(
                    Tag(
                        id = id ?: com.xxjz.ledger.domain.model.Ids.newId("tag"),
                        name = trimmed,
                        color = target?.color.orEmpty(),
                        usageCount = target?.usageCount ?: 0,
                    ),
                )
                reload()
                _state.update { it.copy(message = if (id == null) "已新建标签「$trimmed」" else "已保存") }
            }
        }

        /** 删标签不动流水，只断开关联。 */
        fun deleteTag(tag: Tag) {
            viewModelScope.launch {
                tagRepository.delete(tag.id)
                reload()
                _state.update { it.copy(message = "已删除标签「${tag.name}」") }
            }
        }

        // ---------------- 商家 ----------------

        fun saveMerchant(
            id: String?,
            name: String,
        ) {
            val trimmed = name.trim()
            if (trimmed.isEmpty()) return fail("商家名称不能为空")
            val existing = _state.value.merchants
            if (existing.any { it.id != id && it.name.equals(trimmed, ignoreCase = true) }) {
                return fail("已经有「$trimmed」这个商家了")
            }
            viewModelScope.launch {
                if (id == null) {
                    merchantRepository.findOrCreate(trimmed)
                } else {
                    merchantRepository.rename(id, trimmed)
                }
                reload()
                _state.update { it.copy(message = if (id == null) "已新建商家「$trimmed」" else "已保存") }
            }
        }

        fun deleteMerchant(merchant: Merchant) {
            viewModelScope.launch {
                merchantRepository.delete(merchant.id)
                reload()
                _state.update { it.copy(message = "已删除商家「${merchant.name}」") }
            }
        }

        /** 该分类下是否还有子分类（本 App 固定两层，看一级子类的 children 即可）。 */
        private fun hasChildren(
            groups: List<CategoryGroup>,
            categoryId: String,
        ): Boolean = groups.any { it.parent.id == categoryId && it.children.isNotEmpty() }

        private fun fail(message: String) {
            _state.update { it.copy(message = message) }
        }

        private companion object {
            const val MAX_NAME = 12
        }
    }
