package com.xxjz.ledger.data.backup

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/** M8 加密管线（AC-68、AC-72）：能加能解、密码错不崩、文件头识别、版本拦截。 */
class BackupCryptoTest {
    private val password = "123456"

    private fun craftHeader(
        magic: String = "XXJZBAK1",
        version: Int = BackupCrypto.FORMAT_VERSION,
        cipherText: ByteArray = byteArrayOf(1, 2, 3),
    ): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { o ->
            o.write(magic.toByteArray(Charsets.US_ASCII))
            o.writeInt(version)
            o.writeInt(BackupCrypto.ITERATIONS)
            o.writeInt(16)
            o.write(ByteArray(16) { it.toByte() })
            o.writeInt(12)
            o.write(ByteArray(12) { (it + 1).toByte() })
            o.write(cipherText)
        }
        return out.toByteArray()
    }

    @Test
    @DisplayName("加密后能用同一密码解回原文")
    fun roundTrip() {
        val plain = "一心记账 backup payload 中文也要正常".toByteArray(Charsets.UTF_8)
        val file = BackupCrypto.encrypt(plain, password)
        assertArrayEquals(plain, BackupCrypto.decrypt(file, password))
    }

    @Test
    @DisplayName("文件头是明文可见的 magic，且带盐与 IV")
    fun headerLayout() {
        val file = BackupCrypto.encrypt(byteArrayOf(9, 9, 9), password)
        assertTrue(BackupCrypto.looksLikeBackup(file))
        assertTrue(file.size > BackupCrypto.MAGIC.size + 12 + BackupCrypto.SALT_BYTES + BackupCrypto.IV_BYTES)
        // 前 8 字节就是 magic
        assertArrayEquals(BackupCrypto.MAGIC, file.copyOfRange(0, BackupCrypto.MAGIC.size))
    }

    @Test
    @DisplayName("两次加密同一明文得到的密文不同（盐与 IV 都是随机的）")
    fun randomized() {
        val plain = "same".toByteArray(Charsets.UTF_8)
        assertFalse(BackupCrypto.encrypt(plain, password).contentEquals(BackupCrypto.encrypt(plain, password)))
    }

    @Test
    @DisplayName("密码错误抛 WRONG_PASSWORD，绝不把 AEADBadTagException 漏给上层（AC-72）")
    fun wrongPassword() {
        val file = BackupCrypto.encrypt("hello".toByteArray(Charsets.UTF_8), password)
        val e = assertThrows<BackupException> { BackupCrypto.decrypt(file, "654321") }
        assertEquals(BackupFailure.WRONG_PASSWORD, e.failure)
    }

    @Test
    @DisplayName("密文被改一个字节 → 仍然按「密码错或已损坏」处理，不崩")
    fun tampered() {
        val file = BackupCrypto.encrypt("hello world".toByteArray(Charsets.UTF_8), password)
        file[file.lastIndex] = (file[file.lastIndex] + 1).toByte()
        val e = assertThrows<BackupException> { BackupCrypto.decrypt(file, password) }
        assertEquals(BackupFailure.WRONG_PASSWORD, e.failure)
    }

    @Test
    @DisplayName("不是备份文件（magic 不对）→ NOT_BACKUP")
    fun notBackup() {
        val other = "PK\u0003\u0004 this is a zip".toByteArray(Charsets.UTF_8)
        val e = assertThrows<BackupException> { BackupCrypto.decrypt(other, password) }
        assertEquals(BackupFailure.NOT_BACKUP, e.failure)
        assertFalse(BackupCrypto.looksLikeBackup(other))
    }

    @Test
    @DisplayName("文件被截断 → CORRUPT")
    fun truncated() {
        val file = BackupCrypto.encrypt("hello".toByteArray(Charsets.UTF_8), password)
        val cut = file.copyOfRange(0, BackupCrypto.MAGIC.size + 8)
        val e = assertThrows<BackupException> { BackupCrypto.decrypt(cut, password) }
        assertEquals(BackupFailure.CORRUPT, e.failure)
    }

    @Test
    @DisplayName("文件格式版本高于本机 → UNSUPPORTED_VERSION")
    fun newerFormat() {
        val e = assertThrows<BackupException> { BackupCrypto.decrypt(craftHeader(version = 99), password) }
        assertEquals(BackupFailure.UNSUPPORTED_VERSION, e.failure)
    }

    @Test
    @DisplayName("GZIP 往返；非法 gzip 报 CORRUPT")
    fun gzipRoundTrip() {
        val raw = "账目明细".repeat(100).toByteArray(Charsets.UTF_8)
        val packed = BackupCrypto.gzip(raw)
        assertTrue(packed.size < raw.size)
        assertArrayEquals(raw, BackupCrypto.gunzip(packed))

        val e = assertThrows<BackupException> { BackupCrypto.gunzip(byteArrayOf(1, 2, 3, 4)) }
        assertEquals(BackupFailure.CORRUPT, e.failure)
    }

    @Test
    @DisplayName("中文密码同样可用，且与英文密码互不通用")
    fun unicodePassword() {
        val plain = "数据".toByteArray(Charsets.UTF_8)
        val file = BackupCrypto.encrypt(plain, "我的密码123")
        assertArrayEquals(plain, BackupCrypto.decrypt(file, "我的密码123"))
        assertNotEquals(BackupCrypto.encrypt(plain, "我的密码123").size, 0)
        assertEquals(
            BackupFailure.WRONG_PASSWORD,
            assertThrows<BackupException> { BackupCrypto.decrypt(file, "我的密码124") }.failure,
        )
    }
}
