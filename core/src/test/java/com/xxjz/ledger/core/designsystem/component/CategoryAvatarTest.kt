package com.xxjz.ledger.core.designsystem.component

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 分类圆形图标（PRD v1.9.3 第 6.4 条）。
 *
 * 这段逻辑的关键不在「画得好看」，而在两处容易翻车的地方：
 * 1. 关键词表是**顺序扫描**的，具体词必须排在泛词前面，否则「买菜」会被「购物」吃掉；
 * 2. 散列下标必须恒为非负，`Int.MIN_VALUE.hashCode()` 取绝对值仍是负数，
 *    真按 `abs(hash) % 12` 写，某个倒霉的分类名一进页面就 IndexOutOfBounds。
 */
@DisplayName("分类头像：关键词映射与首字兜底")
class CategoryAvatarTest {
    @Test
    fun `常见分类名都命中对应图标`() {
        val cases =
            mapOf(
                "早餐" to CategoryGlyph.RESTAURANT,
                "晚餐" to CategoryGlyph.RESTAURANT,
                "外卖" to CategoryGlyph.RESTAURANT,
                "咖啡" to CategoryGlyph.CAFE,
                "奶茶" to CategoryGlyph.CAFE,
                "超市" to CategoryGlyph.GROCERY,
                "买菜" to CategoryGlyph.GROCERY,
                "房租" to CategoryGlyph.HOME,
                "水电燃气" to CategoryGlyph.HOME,
                "医疗" to CategoryGlyph.HOSPITAL,
                "地铁" to CategoryGlyph.DIRECTIONS_BUS,
                "打车" to CategoryGlyph.DIRECTIONS_BUS,
                "购物" to CategoryGlyph.SHOPPING_BAG,
                "话费" to CategoryGlyph.PHONE_IPHONE,
                "会员" to CategoryGlyph.MENU_BOOK,
                "红包" to CategoryGlyph.GIFT,
                "宠物" to CategoryGlyph.PETS,
                "机票" to CategoryGlyph.FLIGHT,
                "健身" to CategoryGlyph.FITNESS_CENTER,
                "工资" to CategoryGlyph.PAYMENTS,
                "奖金" to CategoryGlyph.EMOJI_EVENTS,
                "利息" to CategoryGlyph.TRENDING_UP,
                "退款" to CategoryGlyph.REPLAY,
                "电影" to CategoryGlyph.SPORTS_ESPORTS,
            )
        cases.forEach { (name, expected) ->
            assertEquals(expected, glyphOf(name), "「$name」应该命中 $expected")
        }
    }

    @Test
    fun `买菜不会被购物抢走`() {
        // 「超市买菜」同时含「超市」与「买菜」，两条都指向 GROCERY；但「买菜」绝不能落到 SHOPPING_BAG
        assertEquals(CategoryGlyph.GROCERY, glyphOf("买菜"))
        assertEquals(CategoryGlyph.GROCERY, glyphOf("超市买菜"))
        assertEquals(CategoryGlyph.SHOPPING_BAG, glyphOf("购物"))
    }

    @Test
    fun `猜不到的分类退回首字而不是乱猜`() {
        assertEquals(CategoryGlyph.NONE, glyphOf("某某"))
        assertEquals(CategoryGlyph.NONE, glyphOf(""))
        assertEquals(CategoryGlyph.NONE, glyphOf("   "))
        // 猜不到时调用方画首字，这里只保证「不硬套一个无关图标」
        assertEquals("某", initialOf("某某"))
    }

    @Test
    fun `首字按码点取，不把 emoji 劈成半个`() {
        // take(1) 会把代理对切成半个字符，渲染成一个「?」方框
        assertEquals("🍜", initialOf("🍜面"))
        assertEquals("?", initialOf(""), "空名字不能返回空串，否则圆点里一片空白")
    }

    @Test
    fun `散列下标恒为非负`() {
        val names =
            listOf(
                "餐饮",
                "现金",
                "",
                // 这个字符串的 hashCode 恰好是 Int.MIN_VALUE，取绝对值仍是负数
                "polygenelubricants",
                "a".repeat(300),
            )
        names.forEach { name ->
            val index = paletteIndexOf(name)
            assertTrue(index in CategoryPalette.indices, "「$name」算出 $index，越界了")
        }
    }

    @Test
    fun `同一个名字每次都落到同一个颜色`() {
        // 底色若不稳定，每次重组分类圆点的颜色都在闪
        assertEquals(paletteIndexOf("餐饮"), paletteIndexOf("餐饮"))
        assertNotNull(avatarColorOf("餐饮", ""))
    }

    @Test
    fun `非法颜色串返回 null 交给调色板兜底`() {
        assertNull(parseHexColor(""))
        assertNull(parseHexColor("wallet"))
        assertNull(parseHexColor("#12"))
        assertNotNull(parseHexColor("#2E7D5B"))
        assertEquals(parseHexColor("#2E7D5B"), parseHexColor("2E7D5B"), "带不带 # 应该解析出同一个颜色")
    }

    @Test
    fun `分类自带颜色优先于散列色`() {
        // 种子数据里 color 全是空串，但用户自建分类时可以填色，填了就必须尊重
        assertEquals(parseHexColor("#2E7D5B"), avatarColorOf("餐饮", "#2E7D5B"))
        assertEquals(parseHexColor("#2E7D5B"), avatarColorOf("餐饮", "#FF2E7D5B"))
    }
}
