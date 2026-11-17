package com.xxjz.ledger.feature.ledger

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextReplacement
import com.xxjz.ledger.core.designsystem.theme.XxjzTheme
import com.xxjz.ledger.domain.model.FeeMode
import com.xxjz.ledger.domain.model.Installment
import com.xxjz.ledger.domain.model.InstallmentScope
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 分期弹层（v1.9.5 第 9 条）。
 *
 * 关键契约是「填几期就当场算给用户看」，以及「已分期的单子只能看不能改」——
 * 后者挡住的是同笔账被重复建计划、每月出两笔的错账。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w360dp-h640dp-xhdpi")
class InstallmentSetupDialogTest {
    @get:Rule
    val compose = createComposeRule()

    private fun render(
        amount: Long = 300_000,
        existing: Installment? = null,
        onConfirm: (Int, Int) -> Unit = { _, _ -> },
    ) {
        compose.setContent {
            XxjzTheme {
                InstallmentSetupDialog(
                    amountCents = amount,
                    existing = existing,
                    onDismiss = {},
                    onConfirm = onConfirm,
                )
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `填 3 期后显示每期金额`() {
        render()
        compose.onNodeWithTag("inst_periods").performTextReplacement("3")
        compose.waitForIdle()

        // 「每期」与「本期」都是 1000（未执行过任何一期），因此正好两处
        compose.onAllNodesWithText("1,000.00", substring = true).assertCountEquals(2)
        compose.onNodeWithTag("inst_current").assertExists()
    }

    @Test
    fun `已执行 1 期时本期是第 2 期`() {
        render()
        compose.onNodeWithTag("inst_periods").performTextReplacement("3")
        compose.onNodeWithTag("inst_paid").performTextReplacement("1")
        compose.waitForIdle()

        compose.onNodeWithText("本期（第 2 期）", substring = true).assertExists()
    }

    @Test
    fun `期数填 1 时确认置灰`() {
        render()
        compose.onNodeWithTag("inst_periods").performTextReplacement("1")
        compose.waitForIdle()

        compose.onNodeWithTag("inst_confirm").assertIsNotEnabled()
    }

    @Test
    fun `期数合法时确认可点`() {
        render()
        compose.onNodeWithTag("inst_periods").performTextReplacement("6")
        compose.waitForIdle()

        compose.onNodeWithTag("inst_confirm").assertIsEnabled()
    }

    @Test
    fun `已执行期数达到总期数时确认置灰`() {
        render()
        compose.onNodeWithTag("inst_periods").performTextReplacement("3")
        compose.onNodeWithTag("inst_paid").performTextReplacement("3")
        compose.waitForIdle()

        compose.onNodeWithTag("inst_confirm").assertIsNotEnabled()
    }

    @Test
    fun `已有分期时只读且不能再次提交`() {
        val plan =
            Installment(
                id = "ins_t1",
                scope = InstallmentScope.TXN,
                txnId = "t1",
                accountId = "credit",
                principalCents = 300_000,
                feeCents = 0,
                feeMode = FeeMode.AVERAGE,
                periods = 3,
                startBillKey = 202611,
                paidPeriods = 1,
            )
        render(existing = plan)

        compose.onNodeWithText("分期详情").assertExists()
        compose.onNodeWithTag("inst_confirm").assertIsNotEnabled()
    }
}
