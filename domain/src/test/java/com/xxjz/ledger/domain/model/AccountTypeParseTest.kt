package com.xxjz.ledger.domain.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * v1.8.0 账户类型解析（PRD D1 / D4）：
 * 5 个新值直通；老库老备份里的 `FUND` 一律落到 SAVINGS；垃圾值兜底 SAVINGS 不抛异常。
 */
@DisplayName("账户类型解析")
class AccountTypeParseTest {
    @Test
    @DisplayName("五种类型都能按名字直通")
    fun knownTypes() {
        AccountType.entries.forEach { type ->
            assertEquals(type, parseAccountType(type.name))
        }
        assertEquals(5, AccountType.entries.size)
    }

    @ParameterizedTest
    @ValueSource(strings = ["cash", " Cash ", "CASH"])
    @DisplayName("大小写与首尾空格容错")
    fun caseInsensitive(raw: String) {
        assertEquals(AccountType.CASH, parseAccountType(raw))
    }

    @Test
    @DisplayName("老版本 FUND 迁移规则：一律视为储蓄卡（D4）")
    fun legacyFund() {
        assertEquals(AccountType.SAVINGS, parseAccountType("FUND"))
        assertEquals(AccountType.SAVINGS, parseAccountType("fund"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "  ", "MONEY", "UNKNOWN", "投资"])
    @DisplayName("未知或空值兜底 SAVINGS，不抛异常")
    fun unknownFallsBack(raw: String) {
        assertEquals(AccountType.SAVINGS, parseAccountType(raw))
    }

    @Test
    @DisplayName("null 兜底 SAVINGS，数据库脏数据不至于让页面崩溃")
    fun nullFallsBack() {
        assertEquals(AccountType.SAVINGS, parseAccountType(null))
    }

    @Test
    @DisplayName("投资盈亏 = 市值 − 成本，成本为 0 时不除零")
    fun profitCalc() {
        val gain = Account(id = "1", name = "基金", type = AccountType.INVEST, costCents = 1_000_00, marketValueCents = 1_200_00)
        assertEquals(200_00, gain.profitCents)
        assertEquals(0.2, gain.profitRate, 1e-9)

        val loss = gain.copy(marketValueCents = 800_00)
        assertEquals(-200_00, loss.profitCents)

        val zeroCost = gain.copy(costCents = 0, marketValueCents = 500_00)
        assertEquals(0.0, zeroCost.profitRate, 1e-9)

        val notInvest = Account(id = "2", name = "现金", type = AccountType.CASH, marketValueCents = 500_00)
        assertEquals(0L, notInvest.profitCents, "非投资账户不参与盈亏计算")
    }
}
