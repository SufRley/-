package com.xxjz.ledger.feature.ledger

import com.xxjz.ledger.core.common.DateUtils
import com.xxjz.ledger.domain.model.Account
import com.xxjz.ledger.domain.model.Category
import com.xxjz.ledger.domain.model.Txn
import com.xxjz.ledger.domain.model.TxnType
import java.time.LocalDate

/** 列表中的一行账目（已带分类与账户的展示信息）。 */
data class TxnRow(
    val txn: Txn,
    val categoryName: String,
    val categoryIcon: String,
    /** 付款 / 转出账户名；账户被删掉时为空串（v1.9.5 第 4 条：行内要能看见账户）。 */
    val accountName: String = "",
    /** 转账的转入账户名；非转账为 null。 */
    val toAccountName: String? = null,
    /** 发生时刻，`HH:mm`（流水要能看出这一天什么时候花的）。 */
    val timeLabel: String = "",
)

/** 按天分组的流水。 */
data class DayGroup(
    val dateKey: Int,
    /** 今天 / 昨天 / 前天。 */
    val title: String,
    /** 11月13日 周四。 */
    val subtitle: String,
    val income: Long,
    val expense: Long,
    val rows: List<TxnRow>,
)

/**
 * 明细列表分组（纯函数，便于单测）。
 *
 * - 组内按时间倒序，组间按日期倒序；
 * - 日小计只统计收入/支出，排除「不计入收支」与转账（AC-12 / AC-26）；
 * - 转账、退款等仍会出现在列表里，只是不计入日小计。
 */
object LedgerGrouper {
    fun group(
        txns: List<Txn>,
        categories: Map<String, Category>,
        today: LocalDate = DateUtils.today(),
        accounts: Map<String, Account> = emptyMap(),
    ): List<DayGroup> {
        val visible = txns.filter { !it.isDeleted && it.type != TxnType.DEBT }
        return visible
            .groupBy { it.dateKey }
            .toSortedMap(reverseOrder())
            .map { (dateKey, list) ->
                val date = runCatching { DateUtils.dateKeyToDate(dateKey) }.getOrElse { today }
                val rows =
                    list.sortedWith(compareByDescending<Txn> { it.dateTime }.thenByDescending { it.createdAt })
                        .map { txn ->
                            val cat = txn.categoryId?.let { categories[it] }
                            TxnRow(
                                txn = txn,
                                categoryName = cat?.name ?: defaultName(txn),
                                categoryIcon = cat?.icon.orEmpty(),
                                accountName = txn.accountId?.let { accounts[it]?.name }.orEmpty(),
                                toAccountName =
                                    if (txn.type == TxnType.TRANSFER) txn.toAccountId?.let { accounts[it]?.name } else null,
                                timeLabel = timeLabelOf(txn.dateTime),
                            )
                        }
                val counted = list.filter { !it.excludedFromStats }
                DayGroup(
                    dateKey = dateKey,
                    title = DateUtils.friendlyDate(date, today),
                    subtitle = "${date.monthValue}月${date.dayOfMonth}日 ${weekdayOf(date)}",
                    income = counted.filter { it.type == TxnType.INCOME }.sumOf { it.amount },
                    expense = counted.filter { it.type == TxnType.EXPENSE }.sumOf { it.amount },
                    rows = rows,
                )
            }
    }

    private fun defaultName(txn: Txn): String =
        when (txn.type) {
            TxnType.TRANSFER -> "转账"
            TxnType.DEBT -> "借贷"
            else -> "未分类"
        }

    /** 行内的时间文案，固定 `HH:mm`：首页按天分组，年月日是重复信息。 */
    fun timeLabelOf(millis: Long): String {
        val dt = DateUtils.toDateTime(millis)
        return "%02d:%02d".format(dt.hour, dt.minute)
    }

    fun weekdayOf(date: LocalDate): String =
        when (date.dayOfWeek.value) {
            1 -> "周一"
            2 -> "周二"
            3 -> "周三"
            4 -> "周四"
            5 -> "周五"
            6 -> "周六"
            else -> "周日"
        }
}
