package com.xxjz.ledger.core.common

/** 桌面快捷方式（AC-84）的三种落点。 */
enum class ShortcutAction {
    /** 打开 App 并直接拉起记账面板。 */
    NEW_EXPENSE,

    /** 跳到报表 Tab。 */
    OPEN_REPORT,

    /** 打开搜索页。 */
    OPEN_SEARCH,
    ;

    companion object {
        /** Intent extra 的键，与 `res/xml/shortcuts.xml` 保持一致。 */
        const val EXTRA = "com.xxjz.ledger.extra.SHORTCUT"

        /** 未知或空值一律当作「普通启动」，不要因此拉起记账面板吓用户一跳。 */
        fun fromExtra(value: String?): ShortcutAction? =
            when (value?.trim()?.uppercase()) {
                "NEW_EXPENSE" -> NEW_EXPENSE
                "OPEN_REPORT" -> OPEN_REPORT
                "OPEN_SEARCH" -> OPEN_SEARCH
                else -> null
            }
    }
}
