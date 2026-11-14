package com.xxjz.ledger.feature.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xxjz.ledger.core.common.DateUtils
import com.xxjz.ledger.domain.model.TxnType

/** 六个选择器弹窗的显隐状态，打包传给 [EditorPickDialogs]，免得参数一长串。 */
data class PickVisibility(
    val category: Boolean,
    val account: Boolean,
    val toAccount: Boolean,
    val debtAccount: Boolean,
    val newDebt: Boolean,
    val date: Boolean,
)

/** 要关掉哪个弹窗。 */
enum class Pick { CATEGORY, ACCOUNT, TO_ACCOUNT, DEBT_ACCOUNT, NEW_DEBT, DATE }

/**
 * 记账页的所有选择器弹窗（v1.9.1 从旧的底部弹层里迁出来单独立户）。
 *
 * 这些弹窗与页面主体没有数据耦合，只依赖 UiState，所以独立成文件最省心。
 */
@Composable
internal fun EditorPickDialogs(
    viewModel: EditorViewModel,
    state: EditorUiState,
    show: PickVisibility,
    newDebtName: String,
    onNewDebtNameChange: (String) -> Unit,
    close: (Pick) -> Unit,
) {
    if (show.category) {
        CategoryPickerDialog(
            tree = state.tree,
            selectedId = state.categoryId,
            onSelect = {
                viewModel.selectCategory(it)
                close(Pick.CATEGORY)
            },
            onDismiss = { close(Pick.CATEGORY) },
        )
    }

    if (show.account) {
        AccountPickerDialog(
            // 借贷类型下资金账户不能是借贷账户本身
            accounts = if (state.type == TxnType.DEBT) state.fundAccounts else state.accounts,
            selectedId = state.accountId,
            title =
                when (state.type) {
                    TxnType.TRANSFER -> "转出账户"
                    TxnType.DEBT -> "资金账户"
                    else -> "选择账户"
                },
            onSelect = {
                viewModel.selectAccount(it)
                close(Pick.ACCOUNT)
            },
            onDismiss = { close(Pick.ACCOUNT) },
        )
    }

    if (show.toAccount) {
        AccountPickerDialog(
            accounts = state.accounts,
            selectedId = state.toAccountId,
            title = "转入账户",
            onSelect = {
                viewModel.selectToAccount(it)
                close(Pick.TO_ACCOUNT)
            },
            onDismiss = { close(Pick.TO_ACCOUNT) },
        )
    }

    if (show.debtAccount) {
        AccountPickerDialog(
            accounts = state.debtAccounts,
            selectedId = state.debtAccountId,
            title = "选择对方账户",
            onSelect = {
                viewModel.selectDebtAccount(it)
                close(Pick.DEBT_ACCOUNT)
            },
            onDismiss = { close(Pick.DEBT_ACCOUNT) },
        )
    }

    if (show.newDebt) {
        NewDebtAccountDialog(
            value = newDebtName,
            onValueChange = onNewDebtNameChange,
            onConfirm = {
                viewModel.createDebtAccount(newDebtName)
                close(Pick.NEW_DEBT)
            },
            onDismiss = { close(Pick.NEW_DEBT) },
        )
    }

    if (show.date) {
        EditorDatePickerDialog(
            initialMillis = state.dateTime,
            onPick = {
                viewModel.setDateTime(it)
                close(Pick.DATE)
            },
            onDismiss = { close(Pick.DATE) },
        )
    }
}

/** 记账页内一键新建借贷账户（PRD 8.3）：只填人名，本金在金额区填。 */
@Composable
private fun NewDebtAccountDialog(
    value: String,
    onValueChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(20.dp)) {
                Text(
                    text = "新建借贷账户",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = value,
                    onValueChange = { onValueChange(it.take(20)) },
                    label = { Text("对方姓名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("new_debt_name"),
                )
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    TextButton(onClick = onConfirm, modifier = Modifier.testTag("new_debt_confirm")) { Text("新建") }
                }
            }
        }
    }
}

/** 日期短文案：今天/昨天/前天，其余显示月/日。 */
internal fun friendlyDate(millis: Long): String {
    val date = DateUtils.toDate(millis)
    val base = DateUtils.friendlyDate(date)
    return if (base == "今天" || base == "昨天" || base == "前天") base else "${date.monthValue}/${date.dayOfMonth}"
}
