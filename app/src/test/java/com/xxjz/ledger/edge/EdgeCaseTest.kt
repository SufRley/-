package com.xxjz.ledger.edge

import com.xxjz.ledger.core.common.DateUtils
import com.xxjz.ledger.core.common.MoneyFormatter
import com.xxjz.ledger.data.backup.BackupCodec
import com.xxjz.ledger.data.backup.BackupCrypto
import com.xxjz.ledger.data.backup.BackupException
import com.xxjz.ledger.data.backup.BackupFailure
import com.xxjz.ledger.domain.model.BackupPayload
import com.xxjz.ledger.domain.model.RecurringCalc
import com.xxjz.ledger.domain.model.RecurringPeriod
import com.xxjz.ledger.domain.model.RecurringRule
import com.xxjz.ledger.domain.model.Txn
import com.xxjz.ledger.domain.model.TxnDto
import com.xxjz.ledger.domain.model.TxnType
import com.xxjz.ledger.feature.editor.CalcEngine
import com.xxjz.ledger.feature.editor.CalcOp
import com.xxjz.ledger.feature.editor.CalcState
import com.xxjz.ledger.feature.report.YearSummaryUseCase
import com.xxjz.ledger.testutil.FakeCategoryRepository
import com.xxjz.ledger.testutil.FakeTxnRepository
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime

/**
 * M10-T6 边界与异常收口。
 *
 * 这批用例对应 DEV_PLAN 里点名要验的六个点：金额 9 位上限、超长备注、
 * 跨年报表、2 月 29 日周期、导入损坏文件、密码错误。
 * 它们都属于「平时不出现，但一旦出错就是数据丢失/崩溃」的路径，值得单独钉死。
 */
@DisplayName("M10 边界与异常")
class EdgeCaseTest {
    /** 与线上 Room 版本无关：备份解析只关心「能不能解出来」，这里给个够用的值。 */
    private val currentSchema = 4

    /** 造一份「有真实内容」的备份载荷：空载荷测不出往返是否真的搬了数据。 */
    private fun payloadWithData(): BackupPayload {
        val at = millis(2026, 5, 20)
        val txn =
            TxnDto(
                id = "t1",
                amount = 1234,
                categoryId = "cat_exp",
                accountId = "acc",
                dateTime = at,
                dateKey = DateUtils.dateKeyOf(at),
            )
        return BackupPayload(
            payloadVersion = BackupPayload.CURRENT_PAYLOAD_VERSION,
            schemaVersion = currentSchema,
            txns = listOf(txn),
        )
    }

    private fun txn(
        id: String,
        amount: Long,
        at: Long,
        type: TxnType = TxnType.EXPENSE,
        categoryId: String = "exp_1",
    ) = Txn(
        id = id,
        type = type,
        amount = amount,
        categoryId = categoryId,
        accountId = "acc",
        dateTime = at,
        dateKey = DateUtils.dateKeyOf(at),
        createdAt = at,
        updatedAt = at,
    )

    private fun millis(
        year: Int,
        month: Int,
        day: Int,
        hour: Int = 12,
    ): Long = DateUtils.toMillis(LocalDate.of(year, month, day), LocalTime.of(hour, 0))

    @Nested
    @DisplayName("金额 9 位上限")
    inner class AmountLimit {
        @Test
        @DisplayName("9 位整数部分刚好可存：999,999,999.99")
        fun exactLimitIsAllowed() {
            val cents = MoneyFormatter.yuanToCents("999999999.99")
            assertEquals(99_999_999_999L, cents)
            assertEquals(false, MoneyFormatter.exceedsLimit(cents!!))
        }

        @Test
        @DisplayName("再多 0.01 就超限")
        fun oneCentOverLimit() {
            val cents = MoneyFormatter.yuanToCents("1000000000.00")
            assertNotNull(cents)
            assertTrue(MoneyFormatter.exceedsLimit(cents!!))
        }

        @Test
        @DisplayName("负数同样按绝对值判上限")
        fun negativeUsesAbsoluteValue() {
            assertTrue(MoneyFormatter.exceedsLimit(MoneyFormatter.yuanToCents("-1000000000")!!))
            assertEquals(false, MoneyFormatter.exceedsLimit(MoneyFormatter.yuanToCents("-999999999.99")!!))
        }

        @Test
        @DisplayName("小数四位按四舍五入到分，不报错")
        fun roundingToCent() {
            assertEquals(1L, MoneyFormatter.yuanToCents("0.005"))
            assertEquals(0L, MoneyFormatter.yuanToCents("0.004"))
            assertEquals(1235L, MoneyFormatter.yuanToCents("12.345"))
        }

        @Test
        @DisplayName("非法输入返回 null，不抛异常")
        fun malformedInputReturnsNull() {
            listOf("", "  ", "-", ".", "-.", "abc", "1.2.3", "1e99999999999").forEach {
                assertNull(MoneyFormatter.yuanToCents(it), "输入 $it 应该被判为非法")
            }
        }

        private fun typed(text: String): CalcState {
            var st = CalcEngine.initial()
            text.forEach { st = CalcEngine.digit(st, it) }
            return st
        }

        @Test
        @DisplayName("计算器：9 位 × 9 位给出错误态而不是溢出成负数")
        fun calcEngineRejectsOverflow() {
            var st = CalcEngine.op(typed("999999999"), CalcOp.MUL)
            st = CalcEngine.op(CalcEngine.evaluate(st.copy(buffer = "999999999")), CalcOp.MUL)
            st = CalcEngine.evaluate(st.copy(buffer = "999999999", ops = listOf(CalcOp.MUL), values = listOf(BigDecimal("999999999"))))
            assertNotNull(st.error, "9 位 × 9 位必须报超限")
            // 出错后继续输入应当自动开新算式，而不是把错误态留在屏幕上
            assertEquals(null, CalcEngine.digit(st, '5').error)
        }

        @Test
        @DisplayName("计算器：输入超过 9 位整数被丢弃，不会悄悄变成别的数")
        fun calcEngineStopsAtNineDigits() {
            val st = typed("1234567890123")
            assertEquals("123456789", st.buffer)
        }

        @Test
        @DisplayName("计算器：除零给出错误态")
        fun calcEngineDivideByZero() {
            var st = CalcEngine.op(typed("8"), CalcOp.DIV)
            st = CalcEngine.evaluate(st.copy(buffer = "0"))
            assertNotNull(st.error)
        }
    }

    @Nested
    @DisplayName("跨年报表")
    inner class CrossYearReport {
        private val txnRepo = FakeTxnRepository()
        private val categoryRepo = FakeCategoryRepository()
        private val useCase = YearSummaryUseCase(txnRepo, categoryRepo)

        @Test
        @DisplayName("12/31 23:59 与次年 1/1 00:00 分属两个年度，不串账")
        fun boundaryBelongsToCorrectYear() =
            runTest {
                val lastMoment = DateUtils.toMillis(LocalDate.of(2025, 12, 31), LocalTime.of(23, 59, 59))
                val firstMoment = DateUtils.toMillis(LocalDate.of(2026, 1, 1), LocalTime.of(0, 0, 0))
                txnRepo.put(txn("a", 100_00, lastMoment))
                txnRepo.put(txn("b", 200_00, firstMoment))

                assertEquals(100_00L, useCase(2025).expense)
                assertEquals(200_00L, useCase(2026).expense)
            }

        @Test
        @DisplayName("与去年对比：去年无数据时变化率返回 null，而不是除零崩掉")
        fun changePercentWithNoBaseYear() =
            runTest {
                txnRepo.put(txn("a", 100_00, millis(2026, 3, 1)))
                val summary = useCase(2026)
                assertEquals(100_00L, summary.expense)
                assertEquals(0L, summary.lastYearExpense)
                assertNull(summary.expenseChange)
            }

        @Test
        @DisplayName("闰年 2 月 29 的流水能落在当年范围内")
        fun leapDayIncluded() =
            runTest {
                val leap = millis(2028, 2, 29)
                txnRepo.put(txn("a", 50_00, leap))
                assertEquals(50_00L, useCase(2028).expense)
            }
    }

    @Nested
    @DisplayName("2 月 29 日周期记账")
    inner class LeapDayRecurring {
        private fun rule(
            nextAt: Long,
            period: RecurringPeriod,
            interval: Int = 1,
        ) = RecurringRule(
            id = "r",
            type = TxnType.EXPENSE,
            amount = 100_00,
            accountId = "acc",
            period = period,
            interval = interval,
            nextRunAt = nextAt,
        )

        @Test
        @DisplayName("按年：2/29 的下一次落到次年 2/28，不会抛异常")
        fun yearlyFromLeapDay() {
            val from = DateUtils.toMillis(LocalDate.of(2028, 2, 29), LocalTime.of(8, 0))
            val next = RecurringCalc.plusOnce(rule(from, RecurringPeriod.YEAR), from)
            assertEquals(LocalDate.of(2029, 2, 28), DateUtils.toDate(next))
            // 时刻保持不变，只回退日期
            assertEquals(8, DateUtils.toDateTime(next).hour)
        }

        @Test
        @DisplayName("按月：1/31 的下一次落到 2/28（平年）")
        fun monthlyFrom31st() {
            val from = DateUtils.toMillis(LocalDate.of(2026, 1, 31), LocalTime.of(21, 30))
            val next = RecurringCalc.plusOnce(rule(from, RecurringPeriod.MONTH), from)
            assertEquals(LocalDate.of(2026, 2, 28), DateUtils.toDate(next))
            assertEquals(21, DateUtils.toDateTime(next).hour)
            assertEquals(30, DateUtils.toDateTime(next).minute)
        }

        @Test
        @DisplayName("闰年 2/29 起按年连推三年都不掉进非法日期")
        fun yearlyChainStaysValid() {
            var cursor = DateUtils.toMillis(LocalDate.of(2028, 2, 29), LocalTime.of(9, 0))
            val r = rule(cursor, RecurringPeriod.YEAR)
            repeat(3) {
                cursor = RecurringCalc.plusOnce(r, cursor)
                // 能反解出日期就说明没造出 2/30 这种非法值
                assertNotNull(DateUtils.toDate(cursor))
            }
            assertEquals(LocalDate.of(2031, 2, 28), DateUtils.toDate(cursor))
        }

        @Test
        @DisplayName("长期未打开：补执行最多 366 条，不会一次生成上万条")
        fun catchUpIsCapped() {
            val start = DateUtils.toMillis(LocalDate.of(2025, 1, 1), LocalTime.of(8, 0))
            val r = rule(start, RecurringPeriod.DAY)
            val plan = RecurringCalc.plan(r, now = millis(2026, 12, 31), maxRuns = RecurringCalc.MAX_CATCH_UP)
            assertEquals(RecurringCalc.MAX_CATCH_UP, plan.runAts.size)
        }
    }

    @Nested
    @DisplayName("导入损坏文件")
    inner class CorruptBackup {
        private val codec = BackupCodec()

        private val goodFile: ByteArray by lazy {
            codec.encode(payloadWithData(), "pw123456")
        }

        @Test
        @DisplayName("空文件：判为不是备份文件")
        fun emptyFile() {
            val e = assertThrows(BackupException::class.java) { codec.decode(ByteArray(0), "pw", currentSchema) }
            assertEquals(BackupFailure.NOT_BACKUP, e.failure)
        }

        @Test
        @DisplayName("随机字节：判为不是备份文件，不崩")
        fun randomBytes() {
            val e = assertThrows(BackupException::class.java) { codec.decode(ByteArray(512) { (it * 7).toByte() }, "pw", currentSchema) }
            assertEquals(BackupFailure.NOT_BACKUP, e.failure)
        }

        @Test
        @DisplayName("截在头部里：判为损坏（连长度字段都没读全）")
        fun truncatedInsideHeader() {
            // 头部约 40 字节，取前 20 字节
            val cut = goodFile.copyOf(20)
            val e = assertThrows(BackupException::class.java) { codec.decode(cut, "pw123456", currentSchema) }
            assertEquals(BackupFailure.CORRUPT, e.failure)
        }

        @Test
        @DisplayName("截在密文里：判为损坏且提示文件不完整，而不是「密码错误」")
        fun truncatedInsideCipherText() {
            val cut = goodFile.copyOf(goodFile.size - 8)
            val e = assertThrows(BackupException::class.java) { codec.decode(cut, "pw123456", currentSchema) }
            assertEquals(BackupFailure.CORRUPT, e.failure)
            assertTrue(e.message!!.contains("不完整"), "文案应指向重新获取文件，实际为：${e.message}")
        }

        @Test
        @DisplayName("密码正确但文件被截断：仍然提示损坏，不会误导用户反复试密码")
        fun correctPasswordButTruncated() {
            val cut = goodFile.copyOf(goodFile.size / 2)
            val e = assertThrows(BackupException::class.java) { codec.decode(cut, "pw123456", currentSchema) }
            assertEquals(BackupFailure.CORRUPT, e.failure)
        }

        @Test
        @DisplayName("中间字节被篡改：判为损坏或密码错误，不会抛原始异常")
        fun tampered() {
            val bad = goodFile.copyOf()
            // 改密文段（跳过 magic + 头部）
            bad[bad.size / 2] = (bad[bad.size / 2] + 1).toByte()
            val e = assertThrows(BackupException::class.java) { codec.decode(bad, "pw123456", currentSchema) }
            assertTrue(e.failure == BackupFailure.CORRUPT || e.failure == BackupFailure.WRONG_PASSWORD)
        }
    }

    @Nested
    @DisplayName("密码错误")
    inner class WrongPassword {
        private val codec = BackupCodec()

        @Test
        @DisplayName("密码错误给出 WRONG_PASSWORD，而不是「文件损坏」")
        fun wrongPasswordIsDistinguished() {
            val file = codec.encode(BackupPayload(payloadVersion = BackupPayload.CURRENT_PAYLOAD_VERSION), "correct-pw")
            val e = assertThrows(BackupException::class.java) { codec.decode(file, "wrong-pw", currentSchema) }
            assertEquals(BackupFailure.WRONG_PASSWORD, e.failure)
        }

        @Test
        @DisplayName("正确密码能原样还原（含一条真实流水）")
        fun correctPasswordRoundTrip() {
            val payload = payloadWithData()
            val file = codec.encode(payload, "correct-pw")
            val back = codec.decode(file, "correct-pw", currentSchema)
            assertEquals(payload.payloadVersion, back.payloadVersion)
            assertEquals(1, back.txns.size)
            assertEquals(payload.txns.first().id, back.txns.first().id)
        }

        @Test
        @DisplayName("空密码也能用（UI 层才拦），至少不会因为空串崩掉")
        fun emptyPasswordDoesNotCrash() {
            val file = codec.encode(payloadWithData(), "")
            val back = codec.decode(file, "", currentSchema)
            assertEquals(BackupPayload.CURRENT_PAYLOAD_VERSION, back.payloadVersion)
        }

        @Test
        @DisplayName("超长密码不崩（PBKDF2 只吃前 8 位字符数组，长密码要被截断处理）")
        fun veryLongPassword() {
            val long = "a".repeat(4096)
            val file = codec.encode(payloadWithData(), long)
            assertNotNull(codec.decode(file, long, currentSchema))
        }

        @Test
        @DisplayName("looksLikeBackup：真实备份返回 true，随机文件返回 false")
        fun looksLikeBackupGuard() {
            val file = codec.encode(payloadWithData(), "pw")
            assertTrue(BackupCrypto.looksLikeBackup(file))
            assertFalse(BackupCrypto.looksLikeBackup(ByteArray(512) { 0x41 }))
        }
    }
}
