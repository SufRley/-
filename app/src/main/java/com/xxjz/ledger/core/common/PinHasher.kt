package com.xxjz.ledger.core.common

import java.security.MessageDigest
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * PIN 锁的凭据处理（AC-86）。
 *
 * 不存明文，也不存裸 SHA：用 PBKDF2-HMAC-SHA256 加随机盐派生，
 * 与备份加密用的是同一套思路，只是迭代次数按「输入 4~6 位数字」的场景调低到 20000
 * （手机上单次校验 30ms 左右，用户感觉不到，但暴力猜也够慢）。
 */
object PinHasher {
    const val MIN_LENGTH = 4
    const val MAX_LENGTH = 8
    private const val ITERATIONS = 20_000
    private const val KEY_BITS = 256

    /** 随机盐（16 字节，hex 编码）。 */
    fun newSalt(random: java.security.SecureRandom = java.security.SecureRandom()): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return bytes.toHex()
    }

    /** 派生 hash。 */
    fun hash(
        pin: String,
        salt: String,
    ): String {
        val spec = PBEKeySpec(pin.toCharArray(), salt.hexToBytes(), ITERATIONS, KEY_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return factory.generateSecret(spec).encoded.toHex()
    }

    /** 校验；用常量时间比较，避免时序侧信道。 */
    fun verify(
        pin: String,
        salt: String,
        expectedHash: String,
    ): Boolean {
        if (salt.isBlank() || expectedHash.isBlank()) return false
        val actual = hash(pin, salt)
        return MessageDigest.isEqual(actual.toByteArray(), expectedHash.toByteArray())
    }

    /** 只允许纯数字，长度 4~8。设置页与解锁页共用同一条规则。 */
    fun isValidFormat(pin: String): Boolean = pin.length in MIN_LENGTH..MAX_LENGTH && pin.all { it.isDigit() }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun String.hexToBytes(): ByteArray =
        ByteArray(length / 2) { i ->
            ((this[i * 2].digitToInt(16) shl 4) or this[i * 2 + 1].digitToInt(16)).toByte()
        }
}
