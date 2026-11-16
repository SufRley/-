package com.xxjz.ledger.navigation

/**
 * 主框架的几处「显隐判定」纯函数（v1.8.0）。
 *
 * 抽成纯函数的原因很实际：这类规则一旦散在 Compose 里，就只能靠截图或手点去验证；
 * 抽出来之后可以用表驱动测试把「哪些页面有底栏 / 有 ☰ / 有 ＋」全量钉死，
 * 后面再加页面时忘了归类会立刻红。
 *
 * v1.9.5 第 2 条把三个 Tab 合进了 [Routes.MAIN] 一个目的地，所以这里判的不再是
 * 「哪个 Tab」，而是「是不是那一个主框架页」；「具体停在哪一页」由 [MainTab] 另行表达。
 */
object MainChrome {
    /** 底部三 Tab 栏：只在主框架页显示。 */
    fun showBottomBar(route: String?): Boolean = route in MAIN_TAB_ROUTES

    /** 左上角 ☰：与底栏同口径，二级页面仍用返回箭头。 */
    fun showDrawer(route: String?): Boolean = route in MAIN_TAB_ROUTES

    /**
     * 悬浮「＋」：**只在明细页**（PRD D9）。
     *
     * 注意与底栏不同：报表页、账户页属于主 Tab，但没有 ＋，所以要带上当前 Tab 一起判
     * （v1.9.5 起三个 Tab 共用一个路由，光看路由已经分不出明细和报表了）。
     */
    fun showFab(
        route: String?,
        tab: MainTab = MainTab.LEDGER,
    ): Boolean = route in MAIN_TAB_ROUTES && tab == MainTab.LEDGER

    /**
     * 是否是二级页（要隐藏底栏、左上角换返回箭头）。
     *
     * 定义上就等于「不是主 Tab」，只此一条。v1.8.1 曾经的写法在这里另挂了一长串
     * `route == Routes.XXX ||` 的白名单，结果把主 Tab `ACCOUNTS` 也写了进去，
     * 导致点「账户」后整条底栏消失。既然 [MAIN_TAB_ROUTES] 已经是唯一事实来源，
     * 这里就不要再重复列举任何路由——多列一条就可能多错一条。
     */
    fun isDetail(route: String?): Boolean = !showBottomBar(route)
}
