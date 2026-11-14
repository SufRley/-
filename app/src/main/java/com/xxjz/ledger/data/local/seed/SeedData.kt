package com.xxjz.ledger.data.local.seed

import com.xxjz.ledger.data.local.entity.AccountEntity
import com.xxjz.ledger.data.local.entity.CategoryEntity
import com.xxjz.ledger.data.local.entity.LedgerEntity
import com.xxjz.ledger.domain.model.AccountType
import com.xxjz.ledger.domain.model.TxnType
import java.util.UUID

/**
 * 预置数据（AC-91）。
 *
 * 首次启动写入：默认账本、默认现金账户、常用分类树（餐饮/交通/购物/居住/娱乐/医疗/教育/人情/其他
 * + 各自小类），以及收入分类。所有 id 用固定前缀，保证多台设备/重装后语义一致。
 */
object SeedData {
    private const val EXP = "exp"
    private const val INC = "inc"

    /** 大类 + 小类定义：`大类名 to 小类列表`。 */
    private val expenseTree: List<Pair<String, List<String>>> =
        listOf(
            "餐饮" to listOf("早餐", "午餐", "晚餐", "外卖", "零食饮料", "聚餐", "咖啡"),
            "交通" to listOf("公交地铁", "打车", "加油", "停车费", "火车飞机", "共享单车"),
            "购物" to listOf("日用品", "服饰", "数码", "家居", "美妆"),
            "居住" to listOf("房租房贷", "水电燃气", "物业费", "宽带话费"),
            "娱乐" to listOf("电影", "游戏", "会员订阅", "旅行", "运动健身"),
            "医疗" to listOf("门诊", "药品", "体检", "保险"),
            "教育" to listOf("书籍", "课程", "学费"),
            "人情" to listOf("红包", "礼物", "请客"),
            "其他" to listOf("宠物", "手续费", "其他支出"),
        )

    private val incomeTree: List<Pair<String, List<String>>> =
        listOf(
            "工资" to listOf("月薪", "奖金", "加班费"),
            "副业" to listOf("兼职", "稿费"),
            "理财" to listOf("利息", "分红"),
            "其他" to listOf("报销", "退款", "红包收入", "其他收入"),
        )

    /** 收入分类中「退款」的固定 id，退款用例会自动生成该分类下的收入。 */
    const val REFUND_CATEGORY_ID = "inc_refund"

    /** 收入分类中「报销」的固定 id，报销确认时生成的收入一律落在该分类（AC-60）。 */
    const val REIMBURSE_CATEGORY_ID = "inc_reimburse"

    const val DEFAULT_ACCOUNT_ID = "acc_default_cash"

    /** 默认账本 id。 */
    const val DEFAULT_LEDGER_ID = "ledger_default"

    fun defaultLedger(): LedgerEntity = LedgerEntity(id = DEFAULT_LEDGER_ID, name = "默认账本", isDefault = true)

    fun defaultAccounts(): List<AccountEntity> =
        listOf(
            AccountEntity(
                id = DEFAULT_ACCOUNT_ID,
                name = "现金",
                type = AccountType.CASH.name,
                initialBalance = 0,
                icon = "wallet",
                color = "#2E7D5B",
                cardLimit = null,
                billDay = null,
                repayDay = null,
                hidden = false,
                sortOrder = 0,
            ),
        )

    /** 完整分类列表（大类在前、小类紧随其后）。 */
    fun categories(): List<CategoryEntity> {
        val result = mutableListOf<CategoryEntity>()
        var order = 0
        expenseTree.forEachIndexed { majorIndex, (majorName, minors) ->
            val majorId = "${EXP}_$majorIndex"
            result +=
                CategoryEntity(
                    id = majorId,
                    name = majorName,
                    parentId = null,
                    type = TxnType.EXPENSE.name,
                    icon = majorId,
                    color = "",
                    sortOrder = order++,
                    hidden = false,
                    pinned = majorIndex == 0,
                    usageCount = 0,
                )
            minors.forEachIndexed { minorIndex, minorName ->
                val minorId = "${majorId}_$minorIndex"
                result +=
                    CategoryEntity(
                        id = minorId,
                        name = minorName,
                        parentId = majorId,
                        type = TxnType.EXPENSE.name,
                        icon = "",
                        color = "",
                        sortOrder = order++,
                        hidden = false,
                        pinned = false,
                        usageCount = 0,
                    )
            }
        }
        order = 0
        incomeTree.forEachIndexed { majorIndex, (majorName, minors) ->
            val majorId = "${INC}_$majorIndex"
            result +=
                CategoryEntity(
                    id = majorId,
                    name = majorName,
                    parentId = null,
                    type = TxnType.INCOME.name,
                    icon = majorId,
                    color = "",
                    sortOrder = order++,
                    hidden = false,
                    pinned = majorIndex == 0,
                    usageCount = 0,
                )
            minors.forEachIndexed { minorIndex, minorName ->
                val minorId = "${majorId}_$minorIndex"
                val id =
                    when (minorName) {
                        "退款" -> REFUND_CATEGORY_ID
                        "报销" -> REIMBURSE_CATEGORY_ID
                        else -> minorId
                    }
                result +=
                    CategoryEntity(
                        id = id,
                        name = minorName,
                        parentId = majorId,
                        type = TxnType.INCOME.name,
                        icon = "",
                        color = "",
                        sortOrder = order++,
                        hidden = false,
                        pinned = false,
                        usageCount = 0,
                    )
            }
        }
        return result
    }

    /** 生成业务 id。 */
    fun newId(prefix: String): String = "$prefix-${UUID.randomUUID()}"
}
