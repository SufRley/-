package com.xxjz.ledger.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.RowScope
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.xxjz.ledger.MainActivity
import com.xxjz.ledger.core.common.DateUtils
import com.xxjz.ledger.core.common.WidgetFormatter
import com.xxjz.ledger.core.common.WidgetSummary
import com.xxjz.ledger.core.common.WidgetSummaryLoader
import com.xxjz.ledger.di.WidgetEntryPoint
import dagger.hilt.android.EntryPointAccessors

/**
 * 本月摘要小组件（AC-83）：4x2，三列分别是支出 / 收入 / 结余，整块可点进 App。
 *
 * 取数失败一律降级成一张空卡片：桌面上的组件崩了会一直显示「加载失败」，
 * 比显示 0 更糟。
 */
class LedgerWidget : GlanceAppWidget() {
    override suspend fun provideGlance(
        context: Context,
        id: GlanceId,
    ) {
        val summary = loadSummary(context)
        provideContent { LedgerWidgetContent(summary, context) }
    }

    private suspend fun loadSummary(context: Context): WidgetSummary =
        WidgetSummaryLoader.load {
            EntryPointAccessors
                .fromApplication(context, WidgetEntryPoint::class.java)
                .widgetSummary()
                .invoke()
        }
}

@Composable
private fun LedgerWidgetContent(
    summary: WidgetSummary,
    context: Context,
) {
    val today = DateUtils.today()
    val lines = WidgetFormatter.lines(summary, monthLabel = "${today.monthValue} 月")
    Column(
        modifier =
            GlanceModifier
                .fillMaxSize()
                .background(WidgetColors.surface)
                .padding(12.dp)
                .clickable(actionStartActivity(android.content.Intent(context, MainActivity::class.java))),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = lines.title,
            style = TextStyle(color = WidgetColors.onSurfaceVariant, fontSize = 12.sp),
            maxLines = 1,
        )
        Spacer(GlanceModifier.height(8.dp))
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            WidgetCell("支出", lines.expenseText, WidgetColors.expense)
            WidgetCell("收入", lines.incomeText, WidgetColors.income)
            WidgetCell("结余", lines.balanceText, WidgetColors.balance)
        }
        Spacer(GlanceModifier.height(8.dp))
        Text(text = "点一下记一笔", style = TextStyle(color = WidgetColors.onSurfaceVariant, fontSize = 11.sp))
    }
}

@Composable
private fun RowScope.WidgetCell(
    label: String,
    value: String,
    valueColor: ColorProvider,
) {
    Column(modifier = GlanceModifier.defaultWeight()) {
        Text(text = label, style = TextStyle(color = WidgetColors.onSurfaceVariant, fontSize = 11.sp), maxLines = 1)
        Text(
            text = value,
            style = TextStyle(color = valueColor, fontSize = 14.sp, fontWeight = FontWeight.Medium),
            maxLines = 1,
        )
    }
}

/** 桌面配色固定，不跟随 App 主题——桌面不一定是深色。 */
private object WidgetColors {
    val surface = ColorProvider(Color(0xFFFFFFFF))
    val onSurfaceVariant = ColorProvider(Color(0xFF6B6B6B))
    val expense = ColorProvider(Color(0xFFE5484D))
    val income = ColorProvider(Color(0xFF2E9E5B))
    val balance = ColorProvider(Color(0xFF1A1A1A))
}

/** 清单里注册的接收者。 */
class LedgerWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = LedgerWidget()
}
