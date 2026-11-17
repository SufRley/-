package com.xxjz.ledger.feature.editor

import com.xxjz.ledger.core.common.AppResult
import com.xxjz.ledger.core.common.DateUtils
import com.xxjz.ledger.core.common.MoneyFormatter
import com.xxjz.ledger.domain.model.BillCycleCalculator
import com.xxjz.ledger.domain.model.DebtAction
import com.xxjz.ledger.domain.model.DebtDirection
import com.xxjz.ledger.domain.model.Ids
import com.xxjz.ledger.domain.model.Installment
import com.xxjz.ledger.domain.model.InstallmentScope
import com.xxjz.ledger.domain.model.TxnType
import com.xxjz.ledger.domain.usecase.DebtAccountDraft
import com.xxjz.ledger.domain.usecase.DebtOpDraft
import com.xxjz.ledger.domain.usecase.TxnDraft
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 记账面板的「提交保存」职责：普通流水 / 组合支付 / 借贷 / 退款的落库，
 * 以及保存后的记忆回写、分期计划创建、连续记账清场。
 *
 * 从 [EditorViewModel] 搬出；与预选委托共用 [EditorRuntime] 上的 state flow 与依赖，行为不变。
 */
internal class EditorSubmitDelegate(
    private val rt: EditorRuntime,
    private val memory: EditorMemoryDelegate,
) {
    /**
     * 记账面板里「还没有借贷账户」时一键新建（PRD 8.3）。
     *
     * 只建人、不带本金：本金在金额区填，保存时一并落成一笔借出/借入流水。
     */
    fun createDebtAccount(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            rt.state.update { it.copy(error = "请填写对方姓名") }
            return
        }
        rt.scope.launch {
            val result =
                rt.saveDebtAccount(
                    DebtAccountDraft(
                        counterparty = trimmed,
                        firstAction =
                            if (rt.state.value.debtDirection == DebtDirection.LEND) {
                                DebtAction.LEND_OUT
                            } else {
                                DebtAction.BORROW_IN
                            },
                    ),
                )
            when (result) {
                is AppResult.Success -> {
                    rt.state.update { it.copy(debtAccountId = result.data, error = null, dirty = true) }
                    rt.events.emit(EditorEvent.Message("已新建借贷账户「$trimmed」"))
                }
                is AppResult.Failure -> rt.state.update { it.copy(error = result.error.message) }
            }
        }
    }

    /**
     * 对当前这笔支出发起退款（PRD 6.8 第 6 枚）。
     *
     * 退款是**另一笔收入流水**，不动原支出：金额、日期、分类都保持原样，
     * 只在 `refundOfTxnId` 上建立关联。所以这里保存成功后只刷新「已退金额」，
     * 页面上的金额输入框一个字符都不改。
     */
    fun submitRefund() {
        val s = rt.state.value
        val originalId = s.editingId ?: return
        val cents = s.refundCents
        if (cents <= 0L) {
            rt.state.update { it.copy(error = "请输入退款金额") }
            return
        }
        rt.scope.launch {
            rt.state.update { it.copy(saving = true, error = null) }
            when (val result = rt.refundTxn(originalId, cents)) {
                is AppResult.Success -> {
                    val total = runCatching { rt.txnRepository.refundedTotal(originalId) }.getOrDefault(0L)
                    rt.state.update { it.copy(saving = false, refundedCents = total, refundText = "") }
                    rt.events.emit(EditorEvent.Message("已记退款 ¥${MoneyFormatter.centsToYuan(cents)}"))
                }
                is AppResult.Failure ->
                    rt.state.update { it.copy(saving = false, error = result.error.message) }
            }
        }
    }

    /**
     * 保存成功后顺手建分期计划。
     *
     * 必须在流水落库之后：`installment.txnId` 要指向真实 id。失败不阻断保存——
     * 账已经记上了，分期没建成就退回去重新点一下，比整笔作废轻得多。
     */
    private suspend fun createInstallment(
        txnId: String,
        s: EditorUiState,
        periods: Int,
    ) {
        val account = s.accounts.firstOrNull { it.id == s.accountId } ?: return
        val cents = s.effectiveAmount ?: return
        val cycle = BillCycleCalculator.of(DateUtils.today(), account.billDay ?: 1, account.repayDay ?: 20)
        runCatching {
            rt.installmentRepository.upsert(
                Installment(
                    id = Ids.newId("ins"),
                    scope = InstallmentScope.TXN,
                    txnId = txnId,
                    accountId = account.id,
                    principalCents = cents,
                    periods = periods,
                    startBillKey = cycle.billKey,
                    createdAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    /**
     * 「再记」成功后清场，页面留在原地。
     *
     * 保留 `type` / `categoryId` / `accountId` / `dateTime`——连续记同一天的同类账是主场景；
     * 金额、备注、照片、标签、商家、报销、退款、分期全部清掉，否则下一笔会拖着上一笔的尾巴。
     */
    private fun resetForNext() {
        rt.state.update {
            it.copy(
                saving = false,
                calc = CalcState(),
                note = "",
                photoPath = null,
                selectedTagIds = emptySet(),
                merchantId = null,
                pendingReimbursement = false,
                excludedFromStats = false,
                refundText = "",
                installmentPeriods = null,
                error = null,
                dirty = false,
            )
        }
    }

    fun submit(stayOpen: Boolean = false) {
        val s = rt.state.value
        if (s.saving) return
        // 组合支付时金额由各账户合计决定，不吃计算器的值
        val cents = s.effectiveAmount
        if (cents == null) {
            val reason =
                if (s.splitMode) "请至少给两个账户填金额" else (s.calc.error ?: "请输入大于 0 的金额")
            rt.state.update { it.copy(error = reason) }
            return
        }
        // 借贷走独立用例：它不是「记一笔收支」，而是一次资金往来，
        // 一条流水要同时改动借贷账户与资金账户（PRD 2.2）
        if (s.type == TxnType.DEBT) {
            submitDebt(s, cents)
            return
        }
        if (s.splitMode) {
            submitSplit(s, stayOpen)
            return
        }
        val draft =
            TxnDraft(
                id = s.editingId,
                type = s.type,
                amountCents = cents,
                categoryId = s.categoryId,
                accountId = s.accountId,
                toAccountId = s.toAccountId,
                feeCents = if (s.type == TxnType.TRANSFER) s.feeCents else 0,
                dateTime = s.dateTime,
                note = s.note,
                excludedFromStats = s.excludedFromStats,
                pendingReimbursement = s.pendingReimbursement,
                merchantId = s.merchantId,
                merchantName = s.merchantName,
                photoPath = s.photoPath,
            )
        rt.scope.launch {
            rt.state.update { it.copy(saving = true, error = null) }
            when (val result = rt.saveTxn(draft)) {
                is AppResult.Success -> {
                    memory.rememberSelection(s)
                    saveTags(result.data, s.selectedTagIds)
                    s.installmentPeriods?.let { createInstallment(result.data, s, it) }
                    if (stayOpen) {
                        resetForNext()
                        rt.events.emit(EditorEvent.SavedAndNext(result.data, cents))
                    } else {
                        rt.state.update { it.copy(saving = false) }
                        rt.events.emit(EditorEvent.Saved(result.data))
                    }
                }
                is AppResult.Failure -> {
                    rt.state.update { it.copy(saving = false, error = result.error.message) }
                }
            }
        }
    }

    /**
     * 组合支付保存：每个账户各生成一条流水（PRD v1.9.1 第 5 条，用户选定「各显示 1 条」）。
     *
     * 这样余额自然各扣各的，明细里也看得出「这笔到底谁付了多少」；
     * 备注追加「组合 i/n」，方便日后一眼认出它们本是一笔。
     *
     * 编辑模式下先把原流水软删除再落新的：一笔账拆成 N 条之后，原来那条已经没有意义了。
     * 放回收站而不是物理删除，用户后悔还能捞回来。
     */
    private fun submitSplit(
        s: EditorUiState,
        stayOpen: Boolean = false,
    ) {
        val parts = s.splits.filterValues { it > 0L }
        if (parts.size < 2) {
            rt.state.update { it.copy(error = "组合支付至少要两个账户各出一部分") }
            return
        }
        rt.scope.launch {
            rt.state.update { it.copy(saving = true, error = null) }
            var lastId: String? = null
            var index = 0
            for ((accountId, cents) in parts) {
                index += 1
                val note =
                    if (parts.size > 1) {
                        "${s.note}（组合 $index/${parts.size}）".trim().take(200)
                    } else {
                        s.note
                    }
                val draft =
                    TxnDraft(
                        type = s.type,
                        amountCents = cents,
                        categoryId = s.categoryId,
                        accountId = accountId,
                        dateTime = s.dateTime,
                        note = note,
                        excludedFromStats = s.excludedFromStats,
                        pendingReimbursement = s.pendingReimbursement,
                        merchantId = s.merchantId,
                        merchantName = s.merchantName,
                        photoPath = s.photoPath,
                    )
                when (val result = rt.saveTxn(draft)) {
                    is AppResult.Success -> {
                        lastId = result.data
                        saveTags(result.data, s.selectedTagIds)
                    }
                    is AppResult.Failure -> {
                        rt.state.update { it.copy(saving = false, error = result.error.message) }
                        return@launch
                    }
                }
            }
            val finalId = lastId
            if (finalId != null) {
                s.editingId?.let { runCatching { rt.txnRepository.softDelete(it, System.currentTimeMillis()) } }
                memory.rememberSelection(s)
                s.installmentPeriods?.let { createInstallment(finalId, s, it) }
                if (stayOpen) {
                    resetForNext()
                    rt.events.emit(EditorEvent.SavedAndNext(finalId, s.splitTotal))
                } else {
                    rt.state.update { it.copy(saving = false) }
                    rt.events.emit(EditorEvent.Saved(finalId))
                }
            }
        }
    }

    /** 标签写入：失败不阻断保存，标签丢总比账没记上好。 */
    private suspend fun saveTags(
        txnId: String,
        tagIds: Set<String>,
    ) {
        if (tagIds.isEmpty()) return
        runCatching { rt.tagRepository.setTxnTags(txnId, tagIds.toList()) }
    }

    /**
     * 借贷保存：只支持「新的借出 / 新的借入」。
     *
     * 收款与还款是对**已有欠款**的动作，必须先看余额，放在借贷账户详情页做（PRD 2.5）。
     * 这样记账面板里只有一条直线：选方向 → 选对方 → 选资金账户 → 金额 → 保存。
     */
    private fun submitDebt(
        s: EditorUiState,
        cents: Long,
    ) {
        val debtId = s.debtAccountId
        if (debtId == null) {
            rt.state.update { it.copy(error = "请选择借贷账户（对方）") }
            return
        }
        val fundId = s.accountId
        if (fundId == null) {
            rt.state.update { it.copy(error = "请选择资金账户") }
            return
        }
        val action =
            if (s.debtDirection == DebtDirection.LEND) DebtAction.LEND_OUT else DebtAction.BORROW_IN
        rt.scope.launch {
            rt.state.update { it.copy(saving = true, error = null) }
            val result =
                rt.recordDebtOp(
                    DebtOpDraft(
                        action = action,
                        debtAccountId = debtId,
                        fundAccountId = fundId,
                        amountCents = cents,
                        at = s.dateTime,
                        note = s.note,
                    ),
                )
            when (result) {
                is AppResult.Success -> {
                    runCatching { rt.settings.setLastTxnType(TxnType.DEBT) }
                    rt.state.update { it.copy(saving = false) }
                    rt.events.emit(EditorEvent.Saved(result.data))
                }
                is AppResult.Failure ->
                    rt.state.update { it.copy(saving = false, error = result.error.message) }
            }
        }
    }
}
