package com.xxjz.ledger.feature.editor

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.outlined.Calculate
import androidx.compose.material.icons.outlined.Dialpad
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 单个数字键的高度。功能列的高度全靠它推出来，改动要连带改 [SaveKeyHeight]。 */
private val KeyHeight = 48.dp

/** 键间距。保存键跨两行，高度必须是 `48 × 2 + 8`，否则右列会比左列短一截。 */
private val KeyGap = 8.dp
private val SaveKeyHeight = KeyHeight * 2 + KeyGap

/** 键盘上方的表达式/金额显示所需的全部内容。 */
internal data class KeypadDisplay(
    val expression: String,
    /** 金额文案（不含 ¥），组合支付时是分摊合计。 */
    val display: String,
    val error: String?,
    /** 金额着色：随类型走（支出红 / 收入绿 / 转账蓝 / 借贷紫）。 */
    val accent: Color,
    /** 组合支付时计算器不许拨——金额由各账户分摊决定，两套真相互相打架。 */
    val inputEnabled: Boolean,
)

/** 键盘右侧功能列的动作。打包成一个对象，免得 [EditorKeypad] 的参数列表长到被 detekt 点名。 */
internal class KeypadActions(
    val onKey: (String) -> Unit,
    val onBackspace: () -> Unit,
    val onBackspaceLong: () -> Unit,
    val onSave: () -> Unit,
    val onSaveAndNext: () -> Unit,
    val onToggleCalculator: () -> Unit,
    /** 保存条件不满足时的提示（缺金额 / 缺分类 / 缺账户）。 */
    val onBlocked: (String) -> Unit,
)

/**
 * 记账键盘（PRD v1.9.3 第 6.9 条）。
 *
 * 结构是手写的 `Row × Column`，**不能用 `LazyVerticalGrid`**：
 * 网格没法让「保存」跨两行，而参考图里保存就是一个纵向占两格的大键。
 *
 * ```
 * Row {
 *   Column(weight = 3f) { 4 行数字，每行 3 个 }   // 1 2 3 / 4 5 6 / 7 8 9 / ⌨ 0 .
 *   Column(weight = 1f) { ⌫ / 再记（或占位）/ 保存(104dp) }
 * }
 * ```
 *
 * 计算器模式下在数字行**上方**插一行 `+ − × ÷`，右侧功能列同步留出等高空白，
 * 这样「保存」始终压在第 3~4 行，切模式时不会上下跳。
 */
@Composable
internal fun EditorKeypad(
    display: KeypadDisplay,
    actions: KeypadActions,
    calculatorMode: Boolean,
    saveBlocker: String?,
    showSaveAndNext: Boolean,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.navigationBarsPadding()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
            // 表达式只在做四则运算时出现；纯数字输入时金额已经在上面分类行里看得到
            if (display.expression.isNotEmpty() || display.error != null) {
                Text(
                    text = display.error ?: display.expression,
                    style = MaterialTheme.typography.bodySmall,
                    color =
                        if (display.error != null) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, top = 6.dp)
                            .testTag("calc_expression"),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(KeyGap),
            ) {
                Column(
                    modifier = Modifier.weight(3f),
                    verticalArrangement = Arrangement.spacedBy(KeyGap),
                ) {
                    if (calculatorMode) {
                        Row(horizontalArrangement = Arrangement.spacedBy(KeyGap)) {
                            OPERATOR_KEYS.forEach { op ->
                                DigitKey(
                                    label = op,
                                    enabled = display.inputEnabled,
                                    background = MaterialTheme.colorScheme.secondaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                    tag = "key_$op",
                                    onClick = { actions.onKey(op) },
                                )
                            }
                        }
                    }
                    listOf("1", "2", "3", "4", "5", "6", "7", "8", "9").chunked(3).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(KeyGap)) {
                            row.forEach { digit ->
                                DigitKey(
                                    label = digit,
                                    enabled = display.inputEnabled,
                                    tag = "key_$digit",
                                    onClick = { actions.onKey(digit) },
                                )
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(KeyGap)) {
                        CalcToggleKey(
                            calculatorMode = calculatorMode,
                            onClick = actions.onToggleCalculator,
                        )
                        DigitKey(
                            label = "0",
                            enabled = display.inputEnabled,
                            tag = "key_0",
                            onClick = { actions.onKey("0") },
                        )
                        DigitKey(
                            label = ".",
                            enabled = display.inputEnabled,
                            tag = "key_.",
                            onClick = { actions.onKey(".") },
                        )
                    }
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(KeyGap),
                ) {
                    // 左侧多出来的运算符行，右侧用等高空白补齐，保存键才不会错位
                    if (calculatorMode) Spacer(Modifier.height(KeyHeight))
                    BackspaceKey(actions)
                    if (showSaveAndNext) {
                        SaveAndNextKey(enabled = display.inputEnabled, onClick = actions.onSaveAndNext)
                    } else {
                        // 编辑模式下不显示「再记」，但同尺寸占位必须留着，否则保存键会变高、整块键盘跳动
                        Spacer(Modifier.height(KeyHeight))
                    }
                    SaveKey(blocker = saveBlocker, onClick = actions.onSave, onBlocked = actions.onBlocked)
                }
            }
        }
    }
}

private val OPERATOR_KEYS = listOf("+", "-", "×", "÷")

/** 数字键：白底胶囊，22sp Medium。禁用时整体降透明度而不是变灰，保持版式稳定。 */
@Composable
private fun RowScope.DigitKey(
    label: String,
    enabled: Boolean,
    tag: String,
    onClick: () -> Unit,
    background: Color = MaterialTheme.colorScheme.surface,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = background.copy(alpha = if (enabled) 1f else 0.4f),
        modifier = Modifier.weight(1f).height(KeyHeight).testTag(tag),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier
                    .fillMaxSize()
                    .combinedClickable(enabled = enabled, onClick = onClick),
        ) {
            Text(
                text = label,
                fontSize = 22.sp,
                fontWeight = FontWeight.Medium,
                color = if (enabled) contentColor else contentColor.copy(alpha = 0.4f),
            )
        }
    }
}

/** 退格：偏紫的 `tertiaryContainer`，长按清空（沿用 v1.9.1 的行为）。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BackspaceKey(actions: KeypadActions) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        modifier = Modifier.fillMaxWidth().height(KeyHeight).testTag("key_backspace"),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier
                    .fillMaxSize()
                    .combinedClickable(onClick = actions.onBackspace, onLongClick = actions.onBackspaceLong),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Backspace,
                contentDescription = "删除",
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/** 「再记」：保存但不关页，连续记同类账。 */
@Composable
private fun SaveAndNextKey(
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = if (enabled) 1f else 0.4f),
        modifier = Modifier.fillMaxWidth().height(KeyHeight).testTag("key_save_next"),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxSize().combinedClickable(enabled = enabled, onClick = onClick),
        ) {
            Text(
                text = "再记",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

/**
 * 「保存」：纵向占两格（104dp）。
 *
 * 条件不满足时**不做成 disabled**：禁用后点击没有反馈，用户只会以为页面卡了。
 * 这里只降透明度，点了照旧回调，由外层把「还缺什么」弹成 Snackbar。
 */
@Composable
private fun SaveKey(
    blocker: String?,
    onClick: () -> Unit,
    onBlocked: (String) -> Unit,
) {
    val ready = blocker == null
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = if (ready) 1f else 0.4f),
        modifier = Modifier.fillMaxWidth().height(SaveKeyHeight).testTag("btn_save"),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier
                    .fillMaxSize()
                    .combinedClickable(onClick = { if (blocker == null) onClick() else onBlocked(blocker) }),
        ) {
            Text(
                text = "保存",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

/** ⌨：数字 / 四则运算切换。图标跟着当前模式换，用户一眼知道按下去会变成什么。 */
@Composable
private fun RowScope.CalcToggleKey(
    calculatorMode: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.weight(1f).height(KeyHeight).testTag("key_calc_toggle"),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize().combinedClickable(onClick = onClick)) {
            Icon(
                imageVector = if (calculatorMode) Icons.Outlined.Dialpad else Icons.Outlined.Calculate,
                contentDescription = if (calculatorMode) "切换数字键盘" else "切换计算器",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** 细分隔线的宽度常量，供页面其它分隔线复用，避免每处各写一个 `1.dp`。 */
internal val EditorDividerHeight = 1.dp

/** 页面内的横向分隔线（备注行上下那种 30% 透明细线）。 */
@Composable
internal fun EditorHairline(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(EditorDividerHeight)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
    )
}
