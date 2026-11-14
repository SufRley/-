package com.xxjz.ledger.feature.editor

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xxjz.ledger.domain.model.Tag

/** 每行最多显示几个标签，剩下的靠横向滑动。 */
private const val TAGS_PER_ROW = 4

/**
 * 常用标签：两行、可左右滑动（PRD v1.9.1 第 5 条）。
 *
 * 为什么是两行而不是一个自动换行的 FlowRow：用户要的就是「两行可以左右滑动」，
 * 固定两行 + 横向滚动，标签再多也不会把计算器顶下去；FlowRow 会越换行越高。
 */
@Composable
internal fun TagStrip(
    tags: List<Tag>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
) {
    if (tags.isEmpty()) return
    // 先按顺序切成两半，每半各自横向滚动，视觉上就是「两行」而不是「一行折两行」
    val rows = tags.chunked(TAGS_PER_ROW.coerceAtLeast((tags.size + 1) / 2))
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth().testTag("tag_strip"),
    ) {
        rows.forEach { rowTags ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            ) {
                rowTags.forEach { tag ->
                    TagChip(
                        tag = tag,
                        selected = tag.id in selected,
                        onClick = { onToggle(tag.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun TagChip(
    tag: Tag,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color =
            if (selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
            },
        modifier = Modifier.testTag("tag_${tag.id}"),
    ) {
        Text(
            text = tag.name,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color =
                if (selected) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

/**
 * 计算器键盘（PRD v1.9.1 第 5 条，最下面一块）。
 *
 * 数字占左三列、运算符独立占最右一列（沿用 v1.8.1 的结论：运算符散在数字行里最容易按错）。
 * 组合支付时整块置灰——那时金额由各账户分摊决定，让计算器还能拨只会制造两套真相。
 */
@Composable
internal fun CalcPad(
    expression: String,
    display: String,
    error: String?,
    accentIncome: Boolean,
    enabled: Boolean,
    onKey: (String) -> Unit,
    onBackspace: () -> Unit,
    onBackspaceLong: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            if (expression.isNotEmpty()) {
                Text(
                    text = expression,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(end = 4.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(end = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!enabled) {
                    Text(
                        text = "金额按上方分摊合计",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = if (error != null) error else "¥$display",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color =
                        when {
                            error != null -> MaterialTheme.colorScheme.error
                            accentIncome -> MaterialTheme.colorScheme.primary
                            enabled -> MaterialTheme.colorScheme.onSurface
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    modifier = Modifier.testTag("amount_pad"),
                )
            }
            Keypad(
                enabled = enabled,
                onKey = onKey,
                onBackspace = onBackspace,
                onBackspaceLong = onBackspaceLong,
            )
        }
    }
}

@Composable
private fun Keypad(
    enabled: Boolean,
    onKey: (String) -> Unit,
    onBackspace: () -> Unit,
    onBackspaceLong: () -> Unit,
) {
    val rows =
        listOf(
            listOf("7", "8", "9", "+"),
            listOf("4", "5", "6", "-"),
            listOf("1", "2", "3", "×"),
            listOf(".", "0", "⌫", "÷"),
        )
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { key ->
                    Key(
                        key = key,
                        enabled = enabled,
                        onKey = onKey,
                        onBackspace = onBackspace,
                        onBackspaceLong = onBackspaceLong,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RowScope.Key(
    key: String,
    enabled: Boolean,
    onKey: (String) -> Unit,
    onBackspace: () -> Unit,
    onBackspaceLong: () -> Unit,
) {
    val isOp = key in OPERATOR_KEYS
    val isBack = key == "⌫"
    Surface(
        shape = RoundedCornerShape(12.dp),
        color =
            when {
                isOp -> MaterialTheme.colorScheme.secondaryContainer
                isBack -> MaterialTheme.colorScheme.surfaceVariant
                else -> MaterialTheme.colorScheme.surface
            },
        modifier =
            Modifier
                .weight(1f)
                .height(48.dp)
                .testTag("key_$key")
                .combinedClickable(
                    enabled = enabled,
                    onClick = { if (isBack) onBackspace() else onKey(key) },
                    onLongClick = { if (isBack) onBackspaceLong() },
                ),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = key,
                style = MaterialTheme.typography.titleMedium,
                color =
                    when {
                        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        isOp -> MaterialTheme.colorScheme.onSecondaryContainer
                        else -> MaterialTheme.colorScheme.onSurface
                    },
            )
        }
    }
}

private val OPERATOR_KEYS = setOf("+", "-", "×", "÷")
