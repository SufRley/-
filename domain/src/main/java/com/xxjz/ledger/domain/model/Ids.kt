package com.xxjz.ledger.domain.model

import java.util.UUID

/**
 * 业务 id 生成与预置数据的固定 id。
 *
 * 这些常量与生成规则被 domain 用例（退款 / 报销自动生成流水）和 feature 层
 * （新建账户 / 分类 / 标签）共同使用，属于领域层契约，不能留在 data 层的种子数据里——
 * 否则 domain 会反向依赖 data。
 */
object Ids {
    /** 收入分类中「退款」的固定 id，退款用例会自动生成该分类下的收入。 */
    const val REFUND_CATEGORY_ID = "inc_refund"

    /** 收入分类中「报销」的固定 id，报销确认时生成的收入一律落在该分类（AC-60）。 */
    const val REIMBURSE_CATEGORY_ID = "inc_reimburse"

    const val DEFAULT_ACCOUNT_ID = "acc_default_cash"

    /** 默认账本 id。 */
    const val DEFAULT_LEDGER_ID = "ledger_default"

    /** 生成业务 id。 */
    fun newId(prefix: String): String = "$prefix-${UUID.randomUUID()}"
}
