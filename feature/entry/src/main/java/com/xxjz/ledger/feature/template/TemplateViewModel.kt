package com.xxjz.ledger.feature.template

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xxjz.ledger.domain.model.Ids
import com.xxjz.ledger.domain.model.Template
import com.xxjz.ledger.domain.model.TxnType
import com.xxjz.ledger.domain.repository.TemplateRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 模板管理（AC-13）：列表 / 新建 / 编辑 / 删除。 */
@HiltViewModel
class TemplateViewModel
    @Inject
    constructor(
        private val repository: TemplateRepository,
    ) : ViewModel() {
        val templates: StateFlow<List<Template>> =
            repository.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

        fun save(
            id: String?,
            name: String,
            amountCents: Long,
            type: TxnType,
            note: String,
        ) {
            val trimmed = name.trim()
            if (trimmed.isEmpty() || amountCents <= 0) return
            viewModelScope.launch {
                val existing = id?.let { repository.byId(it) }
                val template =
                    Template(
                        id = existing?.id ?: Ids.newId("tpl"),
                        name = trimmed.take(20),
                        type = type,
                        amount = amountCents,
                        categoryId = existing?.categoryId,
                        accountId = existing?.accountId,
                        note = note.take(200),
                        sortOrder = existing?.sortOrder ?: ((templates.value.maxOfOrNull { it.sortOrder } ?: 0) + 1),
                    )
                repository.upsert(template)
            }
        }

        fun delete(id: String) {
            viewModelScope.launch { repository.delete(id) }
        }
    }
