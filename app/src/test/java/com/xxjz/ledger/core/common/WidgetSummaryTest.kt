package com.xxjz.ledger.core.common

import com.xxjz.ledger.domain.model.Account
import com.xxjz.ledger.domain.model.AccountType
import com.xxjz.ledger.domain.model.MonthlySummary
import com.xxjz.ledger.domain.usecase.AssetSummary
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/** M9-A 小组件（AC-83）：取数口径与文案格式。 */
@DisplayName("小组件")
class WidgetSummaryTest {
    private fun account(
        id: String,
        balance: Long,
        hidden: Boolean = false,
    ) = Account(id = id, name = id, type = AccountType.FUND, balance = balance, hidden = hidden)

    @Nested
    @DisplayName("汇总口径")
    inner class Builder {
        @Test
        @DisplayName("结余 = 收入 - 支出，支出大于收入时为负")
        fun balance() {
            val s = WidgetSummaryBuilder.build(MonthlySummary(202611, income = 500_00, expense = 800_00, count = 3), AssetSummary(0, 0))
            assertEquals(-300_00, s.balance)
            assertEquals(3, s.count)
            assertFalse(s.isEmpty)
        }

        @Test
        @DisplayName("净资产 = 资产 - 负债，与「我的」页同口径")
        fun netAssets() {
            val s = WidgetSummaryBuilder.build(MonthlySummary(202611, 0, 0, 0), AssetSummary(assets = 1_000_00, liabilities = 300_00))
            assertEquals(700_00, s.netAssets)
            // 负债大于资产时净值为负，符号要一路保留到文案上
            val negative = WidgetSummaryBuilder.build(MonthlySummary(202611, 0, 0, 0), AssetSummary(assets = 100_00, liabilities = 400_00))
            assertEquals(-300_00, negative.netAssets)
            assertEquals("-¥300.00", WidgetFormatter.yuan(negative.netAssets))
        }

        @Test
        @DisplayName("隐藏账户不计入净资产")
        fun hiddenIgnored() {
            val net = WidgetSummaryBuilder.netAssetsOf(listOf(account("a", 500_00), account("b", 200_00, hidden = true)))
            assertEquals(500_00, net)
        }

        @Test
        @DisplayName("一笔没记时判为为空，即使有账户余额")
        fun emptyState() {
            val s = WidgetSummaryBuilder.build(MonthlySummary(202611, 0, 0, 0), AssetSummary(9_999_00, 0))
            assertTrue(s.isEmpty)
        }
    }

    @Nested
    @DisplayName("文案格式")
    inner class Format {
        @Test
        @DisplayName("金额带千分位与 ¥，负数符号在最前")
        fun money() {
            assertEquals("¥0.00", WidgetFormatter.yuan(0))
            assertEquals("¥1,234.56", WidgetFormatter.yuan(123_456))
            assertEquals("-¥1,234.56", WidgetFormatter.yuan(-123_456))
            assertEquals("-¥0.05", WidgetFormatter.yuan(-5))
        }

        @Test
        @DisplayName("加总失败时降级成空卡片，不抛异常，也不误导用户「一分钱都没花」")
        fun fallback() =
            runBlocking {
                val empty = WidgetSummaryLoader.load { error("数据库被锁了") }
                assertEquals(WidgetSummary(), empty)
                assertEquals("11 月 · 还没记账", WidgetFormatter.lines(empty, "11 月").title)
            }

        @Test
        @DisplayName("有记录时标题显示笔数，没有记录时显示引导文案")
        fun title() {
            val some = WidgetFormatter.lines(WidgetSummary(income = 100, expense = 200, count = 2), "11 月")
            assertEquals("11 月 · 记了 2 笔", some.title)
            assertEquals("¥1.00", some.incomeText)
            assertEquals("¥2.00", some.expenseText)
            assertEquals("-¥1.00", some.balanceText)

            val none = WidgetFormatter.lines(WidgetSummary(), "11 月")
            assertEquals("11 月 · 还没记账", none.title)
            assertEquals("¥0.00", none.expenseText)
        }
    }

    @Nested
    @DisplayName("桌面快捷方式")
    inner class Shortcut {
        @Test
        @DisplayName("三种落点都能识别，大小写与空格容错")
        fun parse() {
            assertEquals(ShortcutAction.NEW_EXPENSE, ShortcutAction.fromExtra("new_expense"))
            assertEquals(ShortcutAction.OPEN_REPORT, ShortcutAction.fromExtra(" OPEN_REPORT "))
            assertEquals(ShortcutAction.OPEN_SEARCH, ShortcutAction.fromExtra("Open_Search"))
        }

        @Test
        @DisplayName("未知或空值当作普通启动，不弹面板")
        fun unknown() {
            assertEquals(null, ShortcutAction.fromExtra(null))
            assertEquals(null, ShortcutAction.fromExtra(""))
            assertEquals(null, ShortcutAction.fromExtra("hack"))
        }
    }
}
