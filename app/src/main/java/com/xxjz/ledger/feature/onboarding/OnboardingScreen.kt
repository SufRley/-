package com.xxjz.ledger.feature.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xxjz.ledger.core.common.ReminderPlanner

/**
 * 首次引导 P00（AC-88）。
 *
 * 三步一屏到底：欢迎 → 选默认账户 → 设每日提醒。
 * 没有用 Pager：三步内容都很轻，横向滑动反而让「跳过」的位置变得不可预期；
 * 用按钮推进的好处是每一步只有一个主操作，用户不会迷路。
 */
@Composable
fun OnboardingScreen(
    onDone: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // 完成 / 跳过都写同一个 finished 标记，出口统一在这一处
    LaunchedEffect(state.finished) {
        if (state.finished) onDone()
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 24.dp)
            .testTag("screen_onboarding"),
    ) {
        TopRow(
            step = state.step,
            onSkip = viewModel::skip,
        )
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            when (state.step) {
                OnboardingStep.WELCOME -> WelcomeStep()
                OnboardingStep.DEFAULT_ACCOUNT ->
                    AccountStep(
                        accounts = state.accounts.map { it.id to it.name },
                        selected = state.selectedAccountId,
                        onSelect = viewModel::selectAccount,
                    )
                OnboardingStep.REMINDER ->
                    ReminderStep(
                        enabled = state.reminderEnabled,
                        label = ReminderPlanner.label(state.reminderMinute),
                        onToggle = viewModel::toggleReminder,
                    )
            }
        }
        BottomRow(
            step = state.step,
            onBack = viewModel::back,
            onNext = viewModel::next,
            onFinish = viewModel::finish,
        )
    }
}

@Composable
private fun TopRow(
    step: OnboardingStep,
    onSkip: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(top = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "第 ${step.index} / ${OnboardingStep.TOTAL} 步",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("onboarding_progress"),
        )
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onSkip, modifier = Modifier.testTag("onboarding_skip")) { Text("跳过") }
    }
}

@Composable
private fun BottomRow(
    step: OnboardingStep,
    onBack: () -> Unit,
    onNext: () -> Unit,
    onFinish: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (step != OnboardingStep.WELCOME) {
            OutlinedButton(onClick = onBack, modifier = Modifier.testTag("onboarding_back")) { Text("上一步") }
        }
        Spacer(Modifier.weight(1f))
        if (step == OnboardingStep.REMINDER) {
            Button(onClick = onFinish, modifier = Modifier.testTag("onboarding_finish")) { Text("完成") }
        } else {
            Button(onClick = onNext, modifier = Modifier.testTag("onboarding_next")) { Text("下一步") }
        }
    }
}

@Composable
private fun WelcomeStep() {
    StepTitle("欢迎使用一心记账")
    StepBody(
        "记一笔只要三秒：点「＋」、输金额、选分类。\n" +
            "数据全部存在你自己的手机里，不上传、不联网、没有账号。",
    )
    Spacer(Modifier.height(24.dp))
    listOf(
        "流水 / 账户 / 预算 / 报表，日常记账该有的都有",
        "支持导入导出 CSV 与加密备份，数据永远属于你",
        "桌面小组件一眼看本月支出，不用打开 App",
    ).forEach { line ->
        Row(Modifier.padding(vertical = 6.dp)) {
            Box(Modifier.padding(top = 7.dp).size(6.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
            Text(
                line,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 10.dp),
            )
        }
    }
}

@Composable
private fun AccountStep(
    accounts: List<Pair<String, String>>,
    selected: String?,
    onSelect: (String) -> Unit,
) {
    StepTitle("选一个默认账户")
    StepBody("记账时默认用这个账户，之后随时能在设置里换。")
    Spacer(Modifier.height(12.dp))
    if (accounts.isEmpty()) {
        Text(
            "暂时没有可用账户，可先跳过，稍后在「我的 → 账户」里新建。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("onboarding_no_account"),
        )
    }
    accounts.forEach { (id, name) ->
        Row(
            Modifier
                .fillMaxWidth()
                .testTag("onboarding_account_$id")
                .selectable(selected = selected == id, onClick = { onSelect(id) })
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected == id, onClick = { onSelect(id) })
            Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 4.dp))
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun ReminderStep(
    enabled: Boolean,
    label: String,
    onToggle: (Boolean) -> Unit,
) {
    StepTitle("每天提醒你记账")
    StepBody("到点提醒一次；当天已经记过账就不会打扰你。")
    Spacer(Modifier.height(16.dp))
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onToggle(!enabled) }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("每日记账提醒", style = MaterialTheme.typography.bodyLarge)
            Text(
                if (enabled) "默认 $label" else "已关闭，之后可在设置里打开",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("onboarding_reminder_label"),
            )
        }
        Switch(checked = enabled, onCheckedChange = onToggle, modifier = Modifier.testTag("onboarding_reminder_switch"))
    }
}

@Composable
private fun StepTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 32.dp).testTag("onboarding_title"),
    )
}

@Composable
private fun StepBody(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 12.dp),
    )
}
