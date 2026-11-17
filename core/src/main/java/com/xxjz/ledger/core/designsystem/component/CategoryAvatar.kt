package com.xxjz.ledger.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.material.icons.filled.LocalGroceryStore
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.PhoneIphone
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 分类圆形图标（PRD v1.9.3 第 6.4 条）。
 *
 * 为什么要「关键词映射 + 首字兜底」而不是直接读 `Category.icon`：
 * 那两个字段（`icon` / `color`）从 v1.0 就在表里，但种子数据填的是无意义的占位值——
 * 大类是 `"wallet"` 这样的英文单词，小类干脆是父分类 id，叶子是空串，`color` 全空。
 * 也就是说仓库里**没有一套能直接渲染的图标体系**。
 *
 * 本轮不改数据、不加字段，改为按分类名猜图标：命中关键词表就用对应 Material 图标，
 * 猜不到就画分类名第一个字。好处是用户自建的「健身卡」「猫粮」也能有像样的头像，
 * 坏处是命名太怪时只能退化成首字——这比显示一个统一的灰色方块强。
 */
enum class CategoryGlyph {
    RESTAURANT,
    CAFE,
    GROCERY,
    HOME,
    HOSPITAL,
    GIFT,
    FLIGHT,
    PAYMENTS,
    TRENDING_UP,
    DIRECTIONS_BUS,
    SHOPPING_BAG,
    SPORTS_ESPORTS,
    PHONE_IPHONE,
    MENU_BOOK,
    PETS,
    FITNESS_CENTER,
    EMOJI_EVENTS,
    REPLAY,
    NONE,
}

/**
 * 关键词表，**按顺序扫描，先命中先用**。
 *
 * 顺序有讲究：「早餐」同时含「餐」，两条规则都指向 [CategoryGlyph.RESTAURANT]，谁先都行；
 * 但「买菜」必须排在「购物」之前才不会被吃掉——所以具体词永远写在泛词前面。
 */
internal val GLYPH_KEYWORDS: List<Pair<CategoryGlyph, List<String>>> =
    listOf(
        CategoryGlyph.RESTAURANT to listOf("早餐", "午餐", "晚餐", "夜宵", "餐", "饭", "外卖"),
        CategoryGlyph.GROCERY to listOf("超市", "买菜", "菜场", "生鲜"),
        CategoryGlyph.CAFE to listOf("咖啡", "奶茶", "饮料", "茶", "酒"),
        CategoryGlyph.SHOPPING_BAG to listOf("购物", "日用品", "服饰", "衣服", "鞋", "电器", "数码"),
        CategoryGlyph.DIRECTIONS_BUS to listOf("交通", "地铁", "公交", "打车", "车", "油"),
        CategoryGlyph.HOME to listOf("房租", "居住", "水电", "物业", "家"),
        CategoryGlyph.HOSPITAL to listOf("医疗", "药", "看病", "体检"),
        CategoryGlyph.MENU_BOOK to listOf("教育", "学习", "书", "课程", "软件", "会员"),
        CategoryGlyph.GIFT to listOf("人情", "红包", "礼物", "请客"),
        CategoryGlyph.PETS to listOf("宠物", "猫", "狗"),
        CategoryGlyph.FLIGHT to listOf("旅行", "机票", "酒店", "旅游"),
        CategoryGlyph.FITNESS_CENTER to listOf("运动", "健身", "保养", "维修"),
        CategoryGlyph.PAYMENTS to listOf("工资", "薪", "收入"),
        CategoryGlyph.EMOJI_EVENTS to listOf("奖金", "年终", "奖励"),
        CategoryGlyph.TRENDING_UP to listOf("理财", "利息", "分红", "投资"),
        CategoryGlyph.REPLAY to listOf("退款", "报销", "退货"),
        CategoryGlyph.PHONE_IPHONE to listOf("通讯", "话费", "网费", "流量"),
        CategoryGlyph.SPORTS_ESPORTS to listOf("娱乐", "电影", "游戏", "玩"),
    )

/** 猜图标：命中就返回对应语义，全都不命中返回 [CategoryGlyph.NONE]，由调用方退化画首字。 */
fun glyphOf(name: String): CategoryGlyph {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) return CategoryGlyph.NONE
    return GLYPH_KEYWORDS.firstOrNull { (_, words) -> words.any { trimmed.contains(it) } }?.first ?: CategoryGlyph.NONE
}

/** 12 色柔和底色，够区分相邻分类又不刺眼；白色图标压在上面对比度都过 4.5:1。 */
internal val CategoryPalette: List<Color> =
    listOf(
        Color(0xFFE53935),
        Color(0xFFD81B60),
        Color(0xFF8E24AA),
        Color(0xFF5E35B1),
        Color(0xFF3949AB),
        Color(0xFF1E88E5),
        Color(0xFF00897B),
        Color(0xFF43A047),
        Color(0xFFEF6C00),
        Color(0xFF6D4C41),
        Color(0xFF546E7A),
        Color(0xFF00838F),
    )

/**
 * 按名字散列到调色板下标。
 *
 * 不能用 `abs(hashCode())`：`Int.MIN_VALUE` 取绝对值还是负数，`% 12` 得到负下标，
 * 列表一取就 IndexOutOfBounds。先转 Long 再取模，才不会在某个倒霉的分类名上崩。
 */
fun paletteIndexOf(name: String): Int {
    val size = CategoryPalette.size
    val mod = name.hashCode().toLong() % size
    return ((mod + size) % size).toInt()
}

/** 解析 `#RRGGBB` / `#AARRGGBB`；非法或空串返回 null，由调用方走调色板兜底。 */
fun parseHexColor(raw: String): Color? {
    val text = raw.trim().removePrefix("#")
    val value = text.toLongOrNull(16) ?: return null
    return when (text.length) {
        6 -> Color(value or 0xFF000000L)
        8 -> Color(value)
        else -> null
    }
}

/** 头像底色：分类自带合法颜色就用它，否则按名字散列取一个固定的柔和色。 */
fun avatarColorOf(
    name: String,
    rawColor: String,
): Color = parseHexColor(rawColor) ?: CategoryPalette[paletteIndexOf(name)]

/**
 * 分类圆头像。
 *
 * @param name 分类名；传 null 表示「还没选分类」，画一个灰色问号占位
 * @param rawColor 分类表里的 `color` 字段，可能是空串，交给 [avatarColorOf] 兜底
 */
@Composable
fun CategoryAvatar(
    name: String?,
    rawColor: String,
    modifier: Modifier = Modifier,
    size: Dp = 32.dp,
) {
    val background =
        if (name == null) {
            MaterialTheme.colorScheme.surfaceVariant
        } else {
            avatarColorOf(name, rawColor)
        }
    val glyph = if (name == null) CategoryGlyph.NONE else glyphOf(name)
    Box(
        modifier = modifier.size(size).background(background, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        when {
            name == null ->
                Text(
                    text = "?",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Bold,
                    fontSize = (size.value * 0.42f).sp,
                )
            glyph != CategoryGlyph.NONE ->
                Icon(
                    imageVector = glyph.vector(),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(size * 0.5f),
                )
            // 兜底：分类名第一个字。按 codePoint 取，避免把 emoji / 生僻字劈成半个
            else ->
                Text(
                    text = initialOf(name),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = (size.value * 0.44f).sp,
                )
        }
    }
}

/** 取首个「字符」：`take(1)` 会把代理对（emoji）切成半个乱码，必须按 codePoint 取。 */
internal fun initialOf(name: String): String {
    if (name.isEmpty()) return "?"
    return String(Character.toChars(name.codePointAt(0)))
}

/** 语义枚举 → Material 图标。图标只在这一处映射，换图标不用翻全项目。 */
private fun CategoryGlyph.vector(): ImageVector =
    when (this) {
        CategoryGlyph.RESTAURANT -> Icons.Filled.Restaurant
        CategoryGlyph.CAFE -> Icons.Filled.LocalCafe
        CategoryGlyph.GROCERY -> Icons.Filled.LocalGroceryStore
        CategoryGlyph.HOME -> Icons.Filled.Home
        CategoryGlyph.HOSPITAL -> Icons.Filled.LocalHospital
        CategoryGlyph.GIFT -> Icons.Filled.CardGiftcard
        CategoryGlyph.FLIGHT -> Icons.Filled.Flight
        CategoryGlyph.PAYMENTS -> Icons.Filled.Payments
        CategoryGlyph.TRENDING_UP -> Icons.Filled.TrendingUp
        CategoryGlyph.DIRECTIONS_BUS -> Icons.Filled.DirectionsBus
        CategoryGlyph.SHOPPING_BAG -> Icons.Filled.ShoppingBag
        CategoryGlyph.SPORTS_ESPORTS -> Icons.Filled.SportsEsports
        CategoryGlyph.PHONE_IPHONE -> Icons.Filled.PhoneIphone
        CategoryGlyph.MENU_BOOK -> Icons.Filled.MenuBook
        CategoryGlyph.PETS -> Icons.Filled.Pets
        CategoryGlyph.FITNESS_CENTER -> Icons.Filled.FitnessCenter
        CategoryGlyph.EMOJI_EVENTS -> Icons.Filled.EmojiEvents
        CategoryGlyph.REPLAY -> Icons.Filled.Replay
        CategoryGlyph.NONE -> Icons.Filled.Payments
    }
