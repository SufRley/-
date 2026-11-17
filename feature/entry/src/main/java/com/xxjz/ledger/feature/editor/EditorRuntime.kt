package com.xxjz.ledger.feature.editor

import com.xxjz.ledger.domain.model.TxnType
import com.xxjz.ledger.domain.repository.AccountRepository
import com.xxjz.ledger.domain.repository.CategoryRepository
import com.xxjz.ledger.domain.repository.InstallmentRepository
import com.xxjz.ledger.domain.repository.MerchantRepository
import com.xxjz.ledger.domain.repository.SettingsRepository
import com.xxjz.ledger.domain.repository.TagRepository
import com.xxjz.ledger.domain.repository.TemplateRepository
import com.xxjz.ledger.domain.repository.TxnRepository
import com.xxjz.ledger.domain.usecase.RecordDebtOpUseCase
import com.xxjz.ledger.domain.usecase.RefundTxnUseCase
import com.xxjz.ledger.domain.usecase.SaveDebtAccountUseCase
import com.xxjz.ledger.domain.usecase.SaveTxnUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/** 商家抽屉一次拉多少个候选。够滑两屏，再多就变成「翻通讯录」了。 */
internal const val MERCHANT_SUGGEST_LIMIT = 60

/**
 * 从设置里读回来的「上次用的」，新建时按它预选（v1.9.3 第 2 条）。
 *
 * 从 [EditorViewModel] 搬出，供预选委托与保存委托共用。
 */
internal data class EditorMemory(
    val expenseCategoryId: String? = null,
    val incomeCategoryId: String? = null,
    val payAccountId: String? = null,
    val defaultAccountId: String? = null,
) {
    fun categoryOf(type: TxnType): String? =
        when (type) {
            TxnType.EXPENSE -> expenseCategoryId
            TxnType.INCOME -> incomeCategoryId
            TxnType.TRANSFER, TxnType.DEBT -> null
        }
}

/**
 * 记账面板的共享运行时：状态流、事件流、协程作用域、全部依赖，以及页面内的会话记忆。
 *
 * 拆分的目的是把 [EditorViewModel] 这个 God 文件按职责分成若干委托：
 * 每个委托只拿到它需要的 [EditorRuntime]，通过同一份 state flow 协作，
 * 而 ViewModel 保留公开 API 与构造签名做薄转发——行为不变，UI 与测试都不需要改。
 */
internal class EditorRuntime(
    val state: MutableStateFlow<EditorUiState>,
    val events: MutableSharedFlow<EditorEvent>,
    val scope: CoroutineScope,
    val txnRepository: TxnRepository,
    val categoryRepository: CategoryRepository,
    val accountRepository: AccountRepository,
    val templateRepository: TemplateRepository,
    val saveTxn: SaveTxnUseCase,
    val recordDebtOp: RecordDebtOpUseCase,
    val saveDebtAccount: SaveDebtAccountUseCase,
    val settings: SettingsRepository,
    val tagRepository: TagRepository,
    val merchantRepository: MerchantRepository,
    val installmentRepository: InstallmentRepository,
    val refundTxn: RefundTxnUseCase,
) {
    /** 上次保存时用的值，新建时用来预选。 */
    var memory: EditorMemory? = null

    /**
     * 本次页面生命周期内选过的分类，按类型分开记。
     *
     * 它比 [memory] 优先：用户刚在支出里点过「买菜」，切到收入再切回来，
     * 期望看到的是「买菜」，而不是三天前存的「晚餐」。
     */
    val sessionPicks = mutableMapOf<TxnType, String?>()

    /** 本次页面内选过的付款账户，优先级高于持久化记忆。 */
    var sessionAccountId: String? = null
}
