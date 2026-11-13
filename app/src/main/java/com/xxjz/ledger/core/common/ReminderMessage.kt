package com.xxjz.ledger.core.common

import com.xxjz.ledger.domain.model.Txn

/**
 * 提醒文案的决策（AC-85）。
 *
 * 「今天还没记账哦」这种话如果发给了今天已经记过账的人，第二天就会被用户关掉通知，
 * 所以发之前必须查一次今天有没有流水。这个判断抽成纯函数，单测能直接钉住边界。
 */
object ReminderMessage {
    const val TITLE = "今天还没记账哦"
    const val BODY = "花 10 秒记一笔，心里更有数"

    /** 今天（按 [dateKey] 口径，yyyyMMdd）是否已经有未删除的流水。 */
    fun hasTxnToday(
        txns: List<Txn>,
        dateKey: Int,
    ): Boolean = txns.any { !it.isDeleted && it.dateKey == dateKey }

    /**
     * 是否该发提醒：开关开着 **且** 今天还没记过账。
     *
     * 已经记过就不发——提醒是为了帮用户想起来，不是攒推送次数。
     */
    fun shouldNotify(
        enabled: Boolean,
        txnsToday: List<Txn>,
        dateKey: Int,
    ): Boolean = enabled && !hasTxnToday(txnsToday, dateKey)
}
