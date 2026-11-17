package com.xxjz.ledger.feature.editor

import com.xxjz.ledger.core.common.DateUtils
import com.xxjz.ledger.domain.model.Account
import com.xxjz.ledger.domain.model.AccountType
import com.xxjz.ledger.domain.model.Category
import com.xxjz.ledger.domain.model.DefaultTimeMode
import com.xxjz.ledger.domain.model.TxnType
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalTime

/**
 * 记账面板的「预选记忆」职责：读设置、按记忆/会话选择预填分类与账户、保存后回写记忆。
 *
 * 从 [EditorViewModel] 搬出。所有状态（state flow、memory、sessionPicks）都在共享的
 * [EditorRuntime] 上，因此与字段选择、提交委托读取到的是同一份数据，行为不变。
 */
internal class EditorMemoryDelegate(private val rt: EditorRuntime) {
    /**
     * 读一次设置：默认时间 + 记忆的分类/账户。
     *
     * 合并成一次 `settings.current()` 调用，别为了两个用途读两遍 DataStore。
     */
    fun loadPreferences() {
        rt.scope.launch {
            val snapshot = runCatching { rt.settings.current() }.getOrNull() ?: return@launch
            rt.memory =
                EditorMemory(
                    expenseCategoryId = snapshot.lastExpenseCategoryId,
                    incomeCategoryId = snapshot.lastIncomeCategoryId,
                    payAccountId = snapshot.lastPayAccountId,
                    defaultAccountId = snapshot.defaultAccountId,
                )
            if (snapshot.defaultTimeMode == DefaultTimeMode.FIXED) {
                val minutes = snapshot.fixedTimeMinutes
                val time = LocalTime.of(minutes / 60 % 24, minutes % 60)
                rt.state.update { it.copy(dateTime = DateUtils.toMillis(DateUtils.today(), time)) }
            }
            applyMemory()
        }
    }

    /**
     * 把记忆套到「还没被用户动过」的字段上。
     *
     * 分类和账户是两条独立的 Flow，谁先到都得试一次，所以这个方法会被调用多次；
     * 它只填空值，绝不覆盖用户已经选好的东西，也不覆盖编辑模式带进来的原值。
     */
    fun applyMemory() {
        val m = rt.memory ?: return
        rt.state.update { s ->
            if (s.editingId != null) return@update s
            s.copy(
                categoryId = s.categoryId ?: preferredCategory(s.categories, s.type),
                accountId = s.accountId ?: preferredAccount(s.accounts),
            )
        }
    }

    /**
     * 分类优先级：本次会话选过的 → 持久化记忆 → 该类型里用得最多的。
     *
     * 最后那级兜底是为了「从没记过账的新用户」也有个合理默认，而不是空白。
     */
    fun preferredCategory(
        categories: List<Category>,
        type: TxnType,
    ): String? {
        if (type != TxnType.EXPENSE && type != TxnType.INCOME) return null
        val candidates = categories.filter { it.type == type && !it.hidden }
        if (candidates.isEmpty()) return null
        val remembered = rt.sessionPicks[type] ?: rt.memory?.categoryOf(type)
        return candidates.firstOrNull { it.id == remembered }?.id
            ?: candidates
                .sortedWith(compareByDescending<Category> { it.usageCount }.thenBy { it.sortOrder })
                .first()
                .id
    }

    /** 账户优先级：本次会话选过的 → 上次实际用的 → 设置里的默认账户 → 唯一资金账户。 */
    fun preferredAccount(accounts: List<Account>): String? {
        val funds = accounts.filter { it.type != AccountType.DEBT }
        if (funds.isEmpty()) return null
        val m = rt.memory
        val remembered = rt.sessionAccountId ?: m?.payAccountId
        return funds.firstOrNull { it.id == remembered }?.id
            ?: funds.firstOrNull { it.id == m?.defaultAccountId }?.id
            ?: funds.singleOrNull()?.id
    }

    /**
     * 保存成功后记住这次的选择。
     *
     * 只在新建时写：老账目的分类不代表用户当前习惯，写进去等于用历史污染记忆。
     * 组合支付记「出钱最多的那个」账户，那才是下次真正会用的。
     */
    suspend fun rememberSelection(s: EditorUiState) {
        runCatching { rt.settings.setLastTxnType(s.type) }
        if (s.editingId != null) return
        runCatching {
            if (s.type == TxnType.EXPENSE || s.type == TxnType.INCOME) {
                rt.settings.setLastCategory(s.type, s.categoryId)
            }
            val payer = if (s.splitMode) s.splits.maxByOrNull { it.value }?.key else s.accountId
            payer?.let { rt.settings.setLastPayAccount(it) }
        }
    }
}
