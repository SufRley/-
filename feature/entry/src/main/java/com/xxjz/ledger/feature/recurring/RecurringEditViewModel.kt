package com.xxjz.ledger.feature.recurring

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xxjz.ledger.core.common.AppResult
import com.xxjz.ledger.core.common.DateUtils
import com.xxjz.ledger.core.common.MoneyFormatter
import com.xxjz.ledger.domain.model.Account
import com.xxjz.ledger.domain.model.Category
import com.xxjz.ledger.domain.model.RecurringPeriod
import com.xxjz.ledger.domain.model.TxnType
import com.xxjz.ledger.domain.repository.AccountRepository
import com.xxjz.ledger.domain.repository.CategoryRepository
import com.xxjz.ledger.domain.repository.RecurringRepository
import com.xxjz.ledger.domain.usecase.RecurringDraft
import com.xxjz.ledger.domain.usecase.RecurringEndMode
import com.xxjz.ledger.domain.usecase.SaveRecurringUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

/** P17 周期任务编辑状态。 */
data class RecurringEditState(
    val isNew: Boolean = true,
    val type: TxnType = TxnType.EXPENSE,
    val amountText: String = "",
    val note: String = "",
    val categoryId: String? = null,
    val accountId: String? = null,
    val toAccountId: String? = null,
    val period: RecurringPeriod = RecurringPeriod.MONTH,
    val intervalText: String = "1",
    val startDate: LocalDate = DateUtils.today(),
    val endMode: RecurringEndMode = RecurringEndMode.FOREVER,
    val maxTimesText: String = "12",
    val endDate: LocalDate? = null,
    val categories: List<Category> = emptyList(),
    val accounts: List<Account> = emptyList(),
    val error: String? = null,
    val finished: Boolean = false,
) {
    val amountCents: Long? get() = MoneyFormatter.yuanToCents(amountText)

    val canSave: Boolean
        get() = (amountCents ?: 0L) > 0 && accountId != null && (type == TxnType.TRANSFER || categoryId != null)
}

/** P17 周期任务编辑（AC-55、AC-56）。 */
@HiltViewModel
class RecurringEditViewModel
    @Inject
    constructor(
        private val recurringRepository: RecurringRepository,
        private val categoryRepository: CategoryRepository,
        private val accountRepository: AccountRepository,
        private val saveRecurring: SaveRecurringUseCase,
        savedState: SavedStateHandle,
    ) : ViewModel() {
        private val ruleId: String? = savedState.get<String>("ruleId")?.takeIf { it.isNotBlank() }

        private val _state = MutableStateFlow(RecurringEditState(isNew = ruleId == null, startDate = DateUtils.today()))
        val state: StateFlow<RecurringEditState> = _state.asStateFlow()

        init {
            viewModelScope.launch {
                val accounts = accountRepository.observeVisible().first()
                val categories = categoryRepository.listAll()
                val rule = ruleId?.let { recurringRepository.byId(it) }
                _state.update { s ->
                    s.copy(
                        accounts = accounts,
                        categories = categories.filter { it.type == (rule?.type ?: TxnType.EXPENSE) },
                        isNew = rule == null,
                        type = rule?.type ?: s.type,
                        amountText = rule?.let { MoneyFormatter.centsToPlain(it.amount) } ?: s.amountText,
                        note = rule?.note ?: s.note,
                        categoryId = rule?.categoryId ?: s.categoryId,
                        accountId = rule?.accountId ?: accounts.firstOrNull()?.id,
                        toAccountId = rule?.toAccountId,
                        period = rule?.period ?: s.period,
                        intervalText = (rule?.interval ?: 1).toString(),
                        startDate = rule?.let { DateUtils.toDate(it.nextRunAt) } ?: s.startDate,
                        endMode =
                            when {
                                rule?.maxTimes != null -> RecurringEndMode.COUNT
                                rule?.endAt != null -> RecurringEndMode.UNTIL
                                else -> RecurringEndMode.FOREVER
                            },
                        maxTimesText = rule?.maxTimes?.toString() ?: s.maxTimesText,
                        endDate = rule?.endAt?.let { DateUtils.toDate(it) },
                    )
                }
            }
        }

        fun setType(type: TxnType) {
            viewModelScope.launch {
                val categories = categoryRepository.listAll().filter { it.type == type }
                _state.update { it.copy(type = type, categoryId = null, categories = categories, toAccountId = null) }
            }
        }

        fun setAmount(value: String) =
            _state.update { it.copy(amountText = value.filter { c -> c.isDigit() || c == '.' }.take(12), error = null) }

        fun setNote(value: String) = _state.update { it.copy(note = value.take(20)) }

        fun setCategory(id: String) = _state.update { it.copy(categoryId = id, error = null) }

        fun setAccount(id: String) = _state.update { it.copy(accountId = id) }

        fun setToAccount(id: String) = _state.update { it.copy(toAccountId = id) }

        fun setPeriod(period: RecurringPeriod) = _state.update { it.copy(period = period) }

        fun setInterval(value: String) = _state.update { it.copy(intervalText = value.filter { c -> c.isDigit() }.take(2), error = null) }

        fun setStartDate(date: LocalDate) = _state.update { it.copy(startDate = date) }

        fun setEndMode(mode: RecurringEndMode) = _state.update { it.copy(endMode = mode) }

        fun setMaxTimes(value: String) = _state.update { it.copy(maxTimesText = value.filter { c -> c.isDigit() }.take(3), error = null) }

        fun setEndDate(date: LocalDate) = _state.update { it.copy(endDate = date) }

        fun save() {
            val s = _state.value
            val amount = s.amountCents
            if (amount == null || amount <= 0) {
                _state.update { it.copy(error = "请输入金额") }
                return
            }
            val interval = s.intervalText.toIntOrNull()?.coerceAtLeast(1) ?: 1
            val startAt = DateUtils.toMillis(s.startDate, java.time.LocalTime.of(9, 0))
            viewModelScope.launch {
                val result =
                    saveRecurring(
                        RecurringDraft(
                            id = ruleId,
                            type = s.type,
                            amountCents = amount,
                            categoryId = s.categoryId,
                            accountId = s.accountId,
                            toAccountId = s.toAccountId,
                            note = s.note,
                            period = s.period,
                            interval = interval,
                            startAt = startAt,
                            endMode = s.endMode,
                            maxTimes = s.maxTimesText.toIntOrNull(),
                            endAt = s.endDate?.let { DateUtils.toMillis(it, java.time.LocalTime.of(23, 59)) },
                        ),
                    )
                when (result) {
                    is AppResult.Success -> _state.update { it.copy(finished = true) }
                    is AppResult.Failure -> _state.update { it.copy(error = result.error.message) }
                }
            }
        }
    }
