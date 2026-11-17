package com.xxjz.ledger.data.backup

import com.xxjz.ledger.domain.model.AccountDto
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * v1.8.0 备份兼容（PRD 7）。
 *
 * 两条路都要能走通：
 * - **读老备份**（v1.7.0 及更早，账户 type=FUND、无投资三字段）→ 账户全部落成储蓄卡，投资字段取默认值；
 * - **读新备份** → 投资账户的成本/市值/计入开关完整往返。
 *
 * 这里直接比对 DTO ↔ Entity 的映射与 JSON 反序列化，不碰加密层（加密另有 BackupCryptoTest 覆盖）。
 */
@DisplayName("备份兼容")
class BackupCompatTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = false
        }

    @Test
    @DisplayName("老备份 FUND 账户恢复后一律是储蓄卡（D4）")
    fun legacyFundBecomesSavings() {
        val legacy =
            """
            {"id":"acc-1","name":"工资卡","type":"FUND","initialBalance":100000,
             "icon":"card","color":"#2E7D5B","hidden":false,"sortOrder":0}
            """.trimIndent()

        val entity = json.decodeFromString(AccountDto.serializer(), legacy).toEntity()

        assertEquals("SAVINGS", entity.type)
        assertEquals(0L, entity.costCents)
        assertEquals(0L, entity.marketValueCents)
        assertEquals(true, entity.includeInNetAssets)
    }

    @Test
    @DisplayName("老备份缺字段时反序列化不报错，默认值兜底")
    fun legacyMissingNewFields() {
        val legacy = """{"id":"a1","name":"现金","type":"CASH"}"""
        val dto = json.decodeFromString(AccountDto.serializer(), legacy)
        assertEquals(0L, dto.costCents)
        assertEquals(0L, dto.marketValueCents)
        assertEquals(true, dto.includeInNetAssets)
    }

    @Test
    @DisplayName("新备份：投资账户三字段完整往返")
    fun investRoundTrip() {
        val dto =
            AccountDto(
                id = "inv-1",
                name = "沪深300",
                type = "INVEST",
                initialBalance = 0,
                costCents = 5_000_00,
                marketValueCents = 5_600_00,
                includeInNetAssets = true,
            )

        val text = json.encodeToString(AccountDto.serializer(), dto)
        val back = json.decodeFromString(AccountDto.serializer(), text)

        assertEquals("INVEST", back.type)
        assertEquals(5_000_00, back.costCents)
        assertEquals(5_600_00, back.marketValueCents)
        assertEquals(true, back.includeInNetAssets)
    }

    @Test
    @DisplayName("不计入净资产的开关也要能往返，不能被默认值悄悄改写")
    fun includeSwitchRoundTrip() {
        val dto = AccountDto(id = "inv-2", name = "私房钱", type = "INVEST", includeInNetAssets = false)
        val back = json.decodeFromString(AccountDto.serializer(), json.encodeToString(AccountDto.serializer(), dto))
        assertEquals(false, back.includeInNetAssets)
    }

    @Test
    @DisplayName("实体导出 → DTO → 实体 全链路一致，类型不会被降级")
    fun entityRoundTripKeepsType() {
        val entity =
            com.xxjz.ledger.data.local.entity.AccountEntity(
                id = "inv-3",
                name = "基金",
                type = "INVEST",
                initialBalance = 100_00,
                icon = "chart",
                color = "#C79A2E",
                cardLimit = null,
                billDay = null,
                repayDay = null,
                hidden = false,
                sortOrder = 3,
                costCents = 1_000_00,
                marketValueCents = 1_100_00,
                includeInNetAssets = true,
            )

        val back = entity.toDto().toEntity()

        assertEquals(entity.type, back.type)
        assertEquals(entity.costCents, back.costCents)
        assertEquals(entity.marketValueCents, back.marketValueCents)
        assertEquals(entity.includeInNetAssets, back.includeInNetAssets)
        assertEquals(entity.initialBalance, back.initialBalance)
    }
}
