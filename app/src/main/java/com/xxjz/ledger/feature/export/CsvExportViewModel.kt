package com.xxjz.ledger.feature.export

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xxjz.ledger.core.common.DateUtils
import com.xxjz.ledger.data.io.DocumentIo
import com.xxjz.ledger.domain.model.Txn
import com.xxjz.ledger.domain.model.TxnType
import com.xxjz.ledger.domain.repository.AccountRepository
import com.xxjz.ledger.domain.repository.CategoryRepository
import com.xxjz.ledger.domain.repository.TxnRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import javax.inject.Inject

data class CsvExportState(
    val range: CsvRange = CsvRange.MONTH,
    val customStart: LocalDate = DateUtils.today().withDayOfMonth(1),
    val customEnd: LocalDate = DateUtils.today(),
    val fields: List<CsvField> = CsvExporter.DEFAULT_FIELDS,
    /** 预计导出的条数，随范围变化实时刷新。 */
    val previewCount: Int = 0,
    val busy: Boolean = false,
    val exportedName: String? = null,
    val message: String? = null,
) {
    val canExport: Boolean get() = fields.isNotEmpty() && !busy
}

/** P24 CSV 导出（AC-75）。 */
@HiltViewModel
class CsvExportViewModel
    @Inject
    constructor(
        private val txnRepository: TxnRepository,
        private val categoryRepository: CategoryRepository,
        private val accountRepository: AccountRepository,
        private val io: DocumentIo,
    ) : ViewModel() {
        /** 写入用的调度器；测试里替换成 Unconfined，避免真实 IO 线程与断言抢跑。 */
        internal var ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO

        private val _state = MutableStateFlow(CsvExportState())
        val state: StateFlow<CsvExportState> = _state.asStateFlow()

        init {
            refreshCount()
        }

        fun selectRange(range: CsvRange) {
            _state.update { it.copy(range = range) }
            refreshCount()
        }

        fun setCustomRange(
            start: LocalDate,
            end: LocalDate,
        ) {
            _state.update { it.copy(customStart = start, customEnd = end, range = CsvRange.CUSTOM) }
            refreshCount()
        }

        fun toggleField(field: CsvField) {
            _state.update { s ->
                val next = if (s.fields.contains(field)) s.fields - field else s.fields + field
                s.copy(fields = next)
            }
        }

        fun consumeMessage() = _state.update { it.copy(message = null, exportedName = null) }

        private fun currentRange(): LongRange {
            val s = _state.value
            return CsvExporter.rangeOf(s.range, DateUtils.today(), s.customStart, s.customEnd)
        }

        private fun refreshCount() =
            viewModelScope.launch {
                val range = currentRange()
                val rows = txnRepository.listInRange(range.first, range.last).filter { !it.isDeleted }
                _state.update { it.copy(previewCount = rows.size) }
            }

        /** 仅供测试：条数是初始化时拍的快照，用例需要「先备数据、再算一次」时调用。 */
        internal fun refreshCountForTest() = refreshCount()

        /** 导出到用户选定的文件。CSV 一律写 BOM，Excel 打开中文不乱码。 */
        fun exportTo(uriString: String) =
            viewModelScope.launch {
                val s = _state.value
                if (s.fields.isEmpty()) {
                    _state.update { it.copy(message = "至少勾选一个字段") }
                    return@launch
                }
                _state.update { it.copy(busy = true) }
                val range = currentRange()
                val categories = categoryRepository.observeAll().first().associateBy { it.id }
                val accounts = accountRepository.observeAll().first().associateBy { it.id }
                val txns = txnRepository.listInRange(range.first, range.last).filter { !it.isDeleted }

                val rows = txns.map { it.toCsvRow(categories, accounts) }
                val text = CsvExporter.build(rows, s.fields)

                val written =
                    runCatching {
                        withContext(ioDispatcher) {
                            io.openOutput(uriString).use { out -> out.write(text.toByteArray(Charsets.UTF_8)) }
                        }
                        io.displayName(uriString)
                    }
                written.fold(
                    onSuccess = { name ->
                        _state.update {
                            it.copy(busy = false, exportedName = name.ifBlank { CsvExporter.fileName(System.currentTimeMillis()) })
                        }
                    },
                    onFailure = { e ->
                        _state.update { it.copy(busy = false, message = "导出失败：${e.message ?: "无法写入"}") }
                    },
                )
            }

        /** 建议文件名，交给 SAF 预填。 */
        fun suggestedFileName(): String = CsvExporter.fileName(System.currentTimeMillis())

        private fun Txn.toCsvRow(
            categories: Map<String, com.xxjz.ledger.domain.model.Category>,
            accounts: Map<String, com.xxjz.ledger.domain.model.Account>,
        ): CsvRow {
            val typeLabel =
                when (type) {
                    TxnType.EXPENSE -> "支出"
                    TxnType.INCOME -> "收入"
                    TxnType.TRANSFER -> "转账"
                    TxnType.DEBT -> "借贷"
                }
            val categoryName =
                categoryId?.let { categories[it] }?.let { child ->
                    child.parentId?.let { pid -> categories[pid]?.name }?.let { "$it/${child.name}" } ?: child.name
                } ?: ""
            return CsvRow(
                dateTime = dateTime,
                typeLabel = typeLabel,
                category = categoryName,
                account = accountId?.let { accounts[it]?.name } ?: "",
                amountCents = amount,
                note = note,
                merchant = merchantName ?: "",
                tags = "",
                excluded = excludedFromStats,
            )
        }
    }
