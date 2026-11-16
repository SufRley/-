package com.xxjz.ledger.core.designsystem.component

import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * 轻量金额输入框（v1.9.6 第 3、5 条）。
 *
 * 为什么不用 `OutlinedTextField`：它带 label、supporting text、图标槽与一整套 decoration，
 * 是 Material 里最重的输入组件之一。组合支付面板一次要渲染 5 个，整块又躺在全屏滚动容器里，
 * 每敲一个字都要把这 5 个重新测量一遍——这就是「来回点会卡」的一半原因。
 *
 * 另一半原因是热区：上一版把它强压到 `height(52.dp)`，低于 Material3 的 56dp 最小高度，
 * 外框绘制被裁，可点的只剩中间一条，点在上下边缘就没反应。这里固定 56dp，
 * 并且让**整个框**都能聚焦，不是只有文字那一行。
 *
 * 自绘边框而不是套 `OutlinedTextField` 的 shape：只要一个圆角矩形，不要那套聚焦指示器
 * 与浮动标签。聚焦时边框换主色、加粗到 1.5dp，一眼能看出正在填哪个框。
 */
@Composable
fun AmountInput(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "0.00",
    enabled: Boolean = true,
    textAlign: TextAlign = TextAlign.Start,
    /** 打到真正的输入框上，测试才能用 PerformTextInput 这类动作（外层 Box 只有点击语义）。 */
    testTag: String? = null,
) {
    val focusRequester = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    val borderColor =
        when {
            !enabled -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            focused -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.outlineVariant
        }
    Box(
        modifier =
            modifier
                .height(INPUT_HEIGHT)
                .clip(shape)
                .border(if (focused) 1.5.dp else 1.dp, borderColor, shape)
                // 焦点请求挂在整个框上：点边框内侧任意位置都能进光标，不必精准命中文字。
                // 用 pointerInput 而不是 clickable：clickable 会把子节点语义合并到这一层，
                // 里面的输入框就从语义树上消失，测试与无障碍都拿不到「这是个可输入的框」。
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectTapGestures { focusRequester.requestFocus() }
                }
                .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicTextField(
            value = value,
            onValueChange = { onValueChange(sanitizeAmount(it)) },
            enabled = enabled,
            singleLine = true,
            textStyle =
                MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = textAlign,
                ),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .onFocusChanged { focused = it.isFocused }
                    .let { if (testTag != null) it.testTag(testTag) else it },
            decorationBox = { inner ->
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = if (textAlign == TextAlign.End) Alignment.CenterEnd else Alignment.CenterStart,
                ) {
                    if (value.isEmpty()) {
                        Text(
                            text = placeholder,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = textAlign,
                        )
                    }
                    inner()
                }
            },
        )
    }
}

/** Material3 的最小触摸高度，低于它热区会被裁。 */
private val INPUT_HEIGHT = 56.dp

/** 金额最多 12 个字符（含小数点），够到亿级且不会把输入框撑爆。 */
private const val MAX_AMOUNT_LENGTH = 12

/**
 * 金额输入过滤：只留数字与**一个**小数点。
 *
 * 单独提出来是为了能被单测直接钉住——过滤规则一旦松了，`12.3.4` 这种值会一路
 * 传到 `yuanToCents` 返回 null，用户看到的是「金额没生效」而不是「输入不合法」。
 */
internal fun sanitizeAmount(raw: String): String {
    val builder = StringBuilder()
    var hasDot = false
    for (ch in raw) {
        when {
            ch.isDigit() -> builder.append(ch)
            ch == '.' && !hasDot -> {
                hasDot = true
                builder.append(ch)
            }
        }
    }
    return builder.toString().take(MAX_AMOUNT_LENGTH)
}
