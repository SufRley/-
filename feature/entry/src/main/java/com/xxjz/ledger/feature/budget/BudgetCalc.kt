package com.xxjz.ledger.feature.budget

import com.xxjz.ledger.core.common.DateUtils
import com.xxjz.ledger.domain.model.Budget
import com.xxjz.ledger.domain.model.BudgetPeriod
import com.xxjz.ledger.domain.model.BudgetScope
import com.xxjz.ledger.domain.model.Category
import com.xxjz.ledger.domain.model.Txn
import com.xxjz.ledger.domain.model.TxnType
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.roundToInt

/** 预算使用状态。 */
enum class BudgetStatus {
    /** 用量正常。 */
    NORMAL,

    /** 已用 ≥ 80%，提前预警。 */
    WARNING,

    /** 已超支。 */
    OVER,

    /** 额度为 0，等同于没设。 */
    EMPTY,

    /** 已停用，不计入总预算。 */
    DISABLED,
}

/** 单条预算的进度快照。 */
data class BudgetProgress(
    val budget: Budget,
    val used: Long,
    /** 已用百分比，可超过 100。 */
    val percent: Int,
    /** 剩余额度，超支时为负。 */
    val remaining: Long,
    /** 超支金额，未超支为 0。 */
    val over: Long,
    /** 日均可花；已用完、已停用或看的是历史月份时为 null。 */
    val dailyAvailable: Long?,
    val status: BudgetStatus,
    /** 分类范围的可读文案，如「餐饮、交通」。 */
    val categoryLabel: String = "",
    /** 是否沿用此前月份的配置。 */
    val inherited: Boolean = false,
) {
    val overspent: Boolean get() = status == BudgetStatus.OVER

    /** 进度条填充比例，钳在 0..1。 */
    val barFraction: Float
        get() =
            when {
                budget.amount <= 0 -> 0f
                else -> (used.toDouble() / budget.amount).toFloat().coerceIn(0f, 1f)
            }
}

/**
 * 预算的纯计算层（M6）。
 *
 * 口径见 DEV_PLAN M6「关键技术点」：已用金额只算该区间内
 * `type=EXPENSE AND excludedFromStats=0 AND deletedAt IS NULL` 的流水；范围按类别时，
 * 选中的大类要连带其所有子类一起计入。
 */
object BudgetCalc {
    /** 用量达到 80% 进入预警。 */
    const val WARNING_RATIO = 0.8

    /** 预算对应的统计区间：按月取整月，按年取整年。 */
    fun rangeOf(
        budget: Budget,
        monthKey: Int,
    ): LongRange =
        when (budget.period) {
            BudgetPeriod.MONTH -> DateUtils.monthRange(monthKey / 100, monthKey % 100)
            BudgetPeriod.YEAR -> DateUtils.yearRange(monthKey / 100)
        }

    /**
     * 把选中的分类展开成「自身 + 全部后代」。
     *
     * 只选中大类时，大类下的小类要一起算进来，否则「餐饮」预算只统计直接挂在大类上的流水。
     */
    fun expandCategories(
        selected: Collection<String>,
        all: List<Category>,
    ): Set<String> {
        if (selected.isEmpty()) return emptySet()
        val childrenOf = all.groupBy { it.parentId }
        val result = LinkedHashSet<String>()
        val queue = ArrayDeque(selected)
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            if (!result.add(id)) continue
            childrenOf[id]?.forEach { queue.addLast(it.id) }
        }
        return result
    }

    /** 计算某条预算已用了多少（分）。 */
    fun usedOf(
        budget: Budget,
        txns: List<Txn>,
        allCategories: List<Category>,
        monthKey: Int = budget.monthKey,
    ): Long {
        val range = rangeOf(budget, monthKey)
        val scope =
            if (budget.scope == BudgetScope.CATEGORY) {
                expandCategories(budget.categoryIds, allCategories)
            } else {
                null
            }
        return txns
            .asSequence()
            .filter { it.type == TxnType.EXPENSE }
            .filter { !it.isDeleted && !it.excludedFromStats }
            .filter { it.dateTime in range.first..range.last }
            .filter { scope == null || it.categoryId in scope }
            .sumOf { it.amount }
    }

    /**
     * 当月剩余天数（含今天）。
     *
     * 看历史月份返回 0（无从谈「日均」），看未来月份返回整月天数。
     */
    fun remainingDays(
        budget: Budget,
        monthKey: Int,
        today: LocalDate,
    ): Int {
        if (budget.period == BudgetPeriod.YEAR) {
            val year = monthKey / 100
            val end = LocalDate.of(year, 12, 31)
            return when {
                today.year < year -> end.dayOfYear
                today.year > year -> 0
                else -> end.dayOfYear - today.dayOfYear + 1
            }
        }
        val anchor = YearMonth.of(monthKey / 100, monthKey % 100)
        val current = YearMonth.from(today)
        return when {
            anchor.isAfter(current) -> anchor.lengthOfMonth()
            anchor.isBefore(current) -> 0
            else -> anchor.lengthOfMonth() - today.dayOfMonth + 1
        }
    }

    /** 组装进度快照。 */
    fun progress(
        budget: Budget,
        used: Long,
        monthKey: Int,
        today: LocalDate,
        categoryLabel: String = "",
        inherited: Boolean = false,
    ): BudgetProgress {
        val amount = budget.amount
        val over = if (amount > 0 && used > amount) used - amount else 0L
        val remaining = amount - used
        val percent =
            if (amount <= 0) {
                if (used > 0) 100 else 0
            } else {
                (used * 100.0 / amount).roundToInt()
            }
        val status =
            when {
                !budget.enabled -> BudgetStatus.DISABLED
                amount <= 0 -> BudgetStatus.EMPTY
                used > amount -> BudgetStatus.OVER
                used >= amount * WARNING_RATIO -> BudgetStatus.WARNING
                else -> BudgetStatus.NORMAL
            }
        val days = remainingDays(budget, monthKey, today)
        val daily =
            when {
                status == BudgetStatus.OVER || status == BudgetStatus.DISABLED -> null
                remaining <= 0 || days <= 0 -> null
                else -> remaining / days
            }
        return BudgetProgress(
            budget = budget,
            used = used,
            percent = percent,
            remaining = remaining,
            over = over,
            dailyAvailable = daily,
            status = status,
            categoryLabel = categoryLabel,
            inherited = inherited,
        )
    }

    /**
     * 列表排序：超支的置顶，其次按使用比例从高到低，再按已用金额。
     *
     * 停用的排到最后，避免长期不用的预算占据视线。
     */
    fun sort(list: List<BudgetProgress>): List<BudgetProgress> =
        list.sortedWith(
            compareBy<BudgetProgress> { it.status == BudgetStatus.DISABLED }
                .thenByDescending { it.status == BudgetStatus.OVER }
                .thenByDescending { p -> if (p.budget.amount > 0) p.used.toDouble() / p.budget.amount else 0.0 }
                .thenByDescending { it.used },
        )

    /** 汇总多条预算的「总额 / 已用」，供总预算卡与分类卡组使用。 */
    fun summarize(list: List<BudgetProgress>): Pair<Long, Long> =
        list.filter { it.status != BudgetStatus.DISABLED }.fold(0L to 0L) { acc, p ->
            (acc.first + p.budget.amount) to (acc.second + p.used)
        }
}
