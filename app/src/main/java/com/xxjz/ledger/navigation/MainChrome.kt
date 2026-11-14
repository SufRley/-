package com.xxjz.ledger.navigation

/**
 * 主框架的几处「显隐判定」纯函数（v1.8.0）。
 *
 * 抽成纯函数的原因很实际：这类规则一旦散在 Compose 里，就只能靠截图或手点去验证；
 * 抽出来之后可以用表驱动测试把「哪些页面有底栏 / 有 ☰ / 有 ＋」全量钉死，
 * 后面再加页面时忘了归类会立刻红。
 */
object MainChrome {
    /** 底部三 Tab 栏：只在主 Tab 页显示。 */
    fun showBottomBar(route: String?): Boolean = route in MAIN_TAB_ROUTES

    /** 左上角 ☰：与底栏同口径，二级页面仍用返回箭头。 */
    fun showDrawer(route: String?): Boolean = route in MAIN_TAB_ROUTES

    /**
     * 悬浮「＋」：**只在明细页**（PRD D9）。
     *
     * 注意与底栏不同：报表页、账户页属于主 Tab，但没有 ＋，所以单独判一次而不是复用 [showBottomBar]。
     */
    fun showFab(route: String?): Boolean = route == Routes.LEDGER
}
