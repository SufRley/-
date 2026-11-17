package com.xxjz.ledger.data.backup

import com.xxjz.ledger.domain.model.AccountDto
import com.xxjz.ledger.domain.model.BackupPayload
import com.xxjz.ledger.domain.model.BackupSettings
import com.xxjz.ledger.domain.model.BudgetDto
import com.xxjz.ledger.domain.model.CategoryDto
import com.xxjz.ledger.domain.model.DebtDto
import com.xxjz.ledger.domain.model.LedgerDto
import com.xxjz.ledger.domain.model.MerchantDto
import com.xxjz.ledger.domain.model.RecurringRuleDto
import com.xxjz.ledger.domain.model.TagDto
import com.xxjz.ledger.domain.model.TemplateDto
import com.xxjz.ledger.domain.model.TxnDto
import com.xxjz.ledger.domain.model.TxnTagDto
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** M8 备份编解码（AC-68、AC-71）：往返一致、版本拦截、未知字段容忍。 */
class BackupCodecTest {
    private val codec = BackupCodec()
    private val password = "abc123"

    private fun sample() =
        BackupPayload(
            schemaVersion = 4,
            appVersion = "1.4.0",
            createdAt = 1_700_000_000_000,
            settings = BackupSettings(defaultAccountId = "acc-1", themeMode = "DARK", savingGoalCents = 500_000),
            txns =
                listOf(
                    TxnDto(
                        id = "t1",
                        type = "EXPENSE",
                        amount = 12_345,
                        categoryId = "exp_1_0",
                        accountId = "acc-1",
                        dateTime = 1_699_999_000_000,
                        dateKey = 20261113,
                        note = "午饭,带\"引号\"",
                        createdAt = 1_699_999_000_000,
                        updatedAt = 1_699_999_000_000,
                    ),
                    TxnDto(id = "t2", type = "INCOME", amount = 100_000, deletedAt = 1_700_000_000_000),
                ),
            accounts = listOf(AccountDto(id = "acc-1", name = "现金", type = "CASH")),
            categories = listOf(CategoryDto(id = "exp_1_0", name = "餐饮", type = "EXPENSE")),
            tags = listOf(TagDto(id = "tag1", name = "出差")),
            txnTags = listOf(TxnTagDto(txnId = "t1", tagId = "tag1")),
            merchants = listOf(MerchantDto(id = "m1", name = "麦当劳")),
            templates = listOf(TemplateDto(id = "tpl1", name = "早餐", amount = 1_000)),
            budgets = listOf(BudgetDto(id = "b1", monthKey = 202611, amount = 300_000)),
            recurringRules = listOf(RecurringRuleDto(id = "r1", amount = 6_000, period = "MONTH")),
            debts = listOf(DebtDto(id = "d1", direction = "LEND", counterparty = "小王", principal = 50_000)),
            ledgers = listOf(LedgerDto(id = "default", name = "日常", isDefault = true)),
        )

    @Test
    @DisplayName("整体往返：所有表的数据都能原样拿回来")
    fun roundTrip() {
        val file = codec.encode(sample(), password)
        val decoded = codec.decode(file, password, currentSchemaVersion = 4)

        assertEquals(sample(), decoded)
        assertEquals(1, decoded.stats.txnCount) // 回收站里的那条不计入笔数
        assertEquals(1, decoded.stats.accountCount)
        assertEquals(1, decoded.stats.categoryCount)
        assertEquals(1_700_000_000_000, decoded.stats.createdAt)
    }

    @Test
    @DisplayName("密码错误时抛 WRONG_PASSWORD，而不是让序列化/解密异常冒到界面")
    fun wrongPassword() {
        val file = codec.encode(sample(), password)
        val e = assertThrows<BackupException> { codec.decode(file, "wrong!", currentSchemaVersion = 4) }
        assertEquals(BackupFailure.WRONG_PASSWORD, e.failure)
    }

    @Test
    @DisplayName("备份的数据版本高于本机 → 明确提示升级，而不是解析出一半")
    fun newerSchema() {
        val future = sample().copy(schemaVersion = 9)
        val file = codec.encode(future, password)
        val e = assertThrows<BackupException> { codec.decode(file, password, currentSchemaVersion = 4) }
        assertEquals(BackupFailure.UNSUPPORTED_VERSION, e.failure)
        assertTrue(e.message!!.contains("v9"))
    }

    @Test
    @DisplayName("备份格式版本过高 → UNSUPPORTED_VERSION")
    fun newerPayloadFormat() {
        val future = sample().copy(payloadVersion = 99)
        val file = codec.encode(future, password)
        val e = assertThrows<BackupException> { codec.decode(file, password, currentSchemaVersion = 4) }
        assertEquals(BackupFailure.UNSUPPORTED_VERSION, e.failure)
    }

    @Test
    @DisplayName("备份过度老旧（schemaVersion 低于最低支持）→ 提示版本过旧")
    fun tooOld() {
        val old = sample().copy(schemaVersion = 0)
        val file = codec.encode(old, password)
        val e = assertThrows<BackupException> { codec.decode(file, password, currentSchemaVersion = 4, minSchemaVersion = 2) }
        assertEquals(BackupFailure.UNSUPPORTED_VERSION, e.failure)
    }

    @Test
    @DisplayName("新版 App 多写的字段，旧版能忽略（ignoreUnknownKeys）")
    fun unknownFieldsIgnored() {
        val jsonText =
            """
            {"payloadVersion":1,"schemaVersion":4,"createdAt":1,"futureFlag":"x",
             "txns":[{"id":"t1","type":"EXPENSE","amount":100,"extra":{"a":1}}]}
            """.trimIndent()
        val file = BackupCrypto.encrypt(BackupCrypto.gzip(jsonText.toByteArray(Charsets.UTF_8)), password)
        val decoded = codec.decode(file, password, currentSchemaVersion = 4)
        assertEquals(1, decoded.txns.size)
        assertEquals(100, decoded.txns.first().amount)
    }

    @Test
    @DisplayName("内容是乱码 → CORRUPT")
    fun corruptPayload() {
        val file = BackupCrypto.encrypt(BackupCrypto.gzip("{not json".toByteArray(Charsets.UTF_8)), password)
        val e = assertThrows<BackupException> { codec.decode(file, password, currentSchemaVersion = 4) }
        assertEquals(BackupFailure.CORRUPT, e.failure)
    }

    @Test
    @DisplayName("JSON 里字段缺省时用默认值补，不抛异常")
    fun missingFieldsUseDefaults() {
        val minimal = """{"schemaVersion":4}"""
        val file = BackupCrypto.encrypt(BackupCrypto.gzip(minimal.toByteArray(Charsets.UTF_8)), password)
        val decoded = codec.decode(file, password, currentSchemaVersion = 4)
        assertEquals(BackupPayload.CURRENT_PAYLOAD_VERSION, decoded.payloadVersion)
        assertTrue(decoded.txns.isEmpty())
        assertEquals(30, decoded.settings.recycleBinDays)
        // 反序列化后再序列化仍然可用（字段完整、无非法值）
        assertTrue(Json.encodeToString(BackupPayload.serializer(), decoded).contains("\"schemaVersion\":4"))
    }
}
