package com.xxjz.ledger.data.backup

import com.xxjz.ledger.core.common.Logger
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.SecureRandom
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** 备份失败的分类，UI 按此给出对应文案（AC-72 要求绝不能崩）。 */
enum class BackupFailure {
    /** 选的不是备份文件（magic 不对）。 */
    NOT_BACKUP,

    /** 密码不对，或文件被改过（GCM 无法区分）。 */
    WRONG_PASSWORD,

    /** 备份格式版本过高 / 数据库版本过高。 */
    UNSUPPORTED_VERSION,

    /** 文件损坏（截断、解压失败等）。 */
    CORRUPT,
}

/** 备份链路的受控异常；[failure] 给 UI 用。 */
class BackupException(
    val failure: BackupFailure,
    message: String,
) : Exception(message)

/**
 * 备份文件加解密管线（AC-68、AC-72）。
 *
 * 流程：`JSON --GZIP--> 压缩 --AES/GCM--> 密文`，包一层带 magic 的文件头：
 * ```
 * v2: "XXJZBAK1"(8) | formatVersion(4) | iterations(4) | saltLen(4) | salt(16)
 *     | ivLen(4) | iv(12) | cipherLen(4) | ciphertext
 * v1: 同上去掉 cipherLen 字段
 * ```
 *
 * - KDF：PBKDF2-HMAC-SHA256，120000 轮，16 字节随机盐。
 * - 对称加密：AES-256-GCM，12 字节随机 IV，128 位 Tag。
 * - **只用 JDK 自带的 `javax.crypto`**，不引第三方加密库。
 *
 * GCM 的 Tag 校验同时承担「完整性校验」：密码错、文件被截断/篡改，都会在解密时抛
 * [AEADBadTagException]。但两者的处理完全不同（重输密码 vs 重新获取文件），
 * 所以 v2 起在头部写了 `cipherLen`：与实际剩余字节数不符即判定为文件残缺。
 * 读 v1 老文件时没有该字段，只能统一按「密码错误或文件损坏」提示。
 */
object BackupCrypto {
    private const val TAG = "BackupCrypto"

    /** 盐最长 64 字节、IV 最长 32 字节（AES-GCM 标准 IV 是 12 字节，留余量）。 */
    private const val MAX_SALT_LEN = 64
    private const val MAX_IV_LEN = 32

    /** 文件魔数，够长才能可靠区分「选错文件」。 */
    val MAGIC = "XXJZBAK1".toByteArray(Charsets.US_ASCII)

    /** 文件头格式版本：2 = 带 cipherLen 字段（v1 老文件仍可读）。 */
    const val FORMAT_VERSION = 2

    /** 仍可读取的最低文件头版本。 */
    private const val MIN_FORMAT_VERSION = 1

    const val SALT_BYTES = 16
    const val IV_BYTES = 12
    const val KEY_BITS = 256
    const val TAG_BITS = 128
    const val ITERATIONS = 120_000

    private const val KEY_ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    /** 明文 → 备份文件字节。 */
    fun encrypt(
        plain: ByteArray,
        password: String,
        random: SecureRandom = SecureRandom(),
    ): ByteArray {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val key = deriveKey(password, salt, ITERATIONS)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        val cipherText = cipher.doFinal(plain)

        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { o ->
            o.write(MAGIC)
            o.writeInt(FORMAT_VERSION)
            o.writeInt(ITERATIONS)
            o.writeInt(salt.size)
            o.write(salt)
            o.writeInt(iv.size)
            o.write(iv)
            o.writeInt(cipherText.size)
            o.write(cipherText)
        }
        return out.toByteArray()
    }

    /** 备份文件字节 → 明文；任何异常都翻译成 [BackupException]，不向上抛原始异常。 */
    fun decrypt(
        file: ByteArray,
        password: String,
    ): ByteArray {
        val header = readHeader(file)
        val key = deriveKey(password, header.salt, header.iterations)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, header.iv))
        return try {
            cipher.doFinal(header.cipherText)
        } catch (e: AEADBadTagException) {
            // GCM 校验失败只有两种可能：密码不对，或者密文被截断/篡改。
            // 两者对用户的下一步动作完全不同（再输一次 vs 换个文件），得分开报。
            // 判据是「头部声明的密文长度 ≠ 实际读到的长度」——只有 v2 文件有这个字段，
            // v1 老文件没法区分，保持原来的「密码错误或已损坏」措辞。
            val truncated = header.declaredCipherLen != null && header.declaredCipherLen != header.cipherText.size
            Logger.d(TAG, "GCM tag 校验失败，声明=${header.declaredCipherLen} 实际=${header.cipherText.size}", e)
            throw when {
                truncated -> BackupException(BackupFailure.CORRUPT, "备份文件不完整（可能传输中断），请重新获取后导入")
                header.declaredCipherLen == null ->
                    BackupException(BackupFailure.WRONG_PASSWORD, "密码错误或备份文件已损坏")
                else -> BackupException(BackupFailure.WRONG_PASSWORD, "密码错误，请重试")
            }
        } catch (e: Exception) {
            Logger.w(TAG, "解密失败", e)
            throw BackupException(BackupFailure.CORRUPT, "备份文件已损坏，无法解密")
        }
    }

    /** 只做一次轻量判断，供「选文件」后立刻区分是不是备份。 */
    fun looksLikeBackup(file: ByteArray): Boolean = file.size >= MAGIC.size + 12 && file.startsWith(MAGIC)

    /** GZIP 压缩。 */
    fun gzip(raw: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(raw) }
        return out.toByteArray()
    }

    /** GZIP 解压；数据不是合法 gzip 时抛 [BackupFailure.CORRUPT]。 */
    fun gunzip(packed: ByteArray): ByteArray =
        try {
            GZIPInputStream(ByteArrayInputStream(packed)).use { it.readBytes() }
        } catch (e: Exception) {
            Logger.w(TAG, "gzip 解压失败", e)
            throw BackupException(BackupFailure.CORRUPT, "备份内容损坏，无法解压")
        }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        return prefix.indices.all { this[it] == prefix[it] }
    }

    private class Header(
        val iterations: Int,
        val salt: ByteArray,
        val iv: ByteArray,
        val cipherText: ByteArray,
        /** 头部声明的密文长度；v1 老文件没有该字段，为 null。 */
        val declaredCipherLen: Int?,
    )

    private fun readHeader(file: ByteArray): Header {
        if (!file.startsWith(MAGIC)) {
            throw BackupException(BackupFailure.NOT_BACKUP, "这不是「一心记账」的备份文件")
        }
        return try {
            DataInputStream(ByteArrayInputStream(file)).use { input ->
                input.skipBytes(MAGIC.size)
                parseHeader(input)
            }
        } catch (e: BackupException) {
            throw e
        } catch (e: Exception) {
            // EOFException 之类：文件被截断
            Logger.w(TAG, "备份头部读取失败", e)
            throw BackupException(BackupFailure.CORRUPT, "备份文件已损坏")
        }
    }

    /**
     * 逐字段读头部。
     *
     * 长度字段全部走 [readLength]：任何「<=0 或超过上限」都当损坏处理，
     * 否则一个被篡改的长度会让后面的 `ByteArray(n)` 直接 OOM。
     */
    private fun parseHeader(input: DataInputStream): Header {
        val version = input.readInt()
        if (version > FORMAT_VERSION) {
            throw BackupException(BackupFailure.UNSUPPORTED_VERSION, "备份文件来自更新版本的 App，请先升级")
        }
        if (version < MIN_FORMAT_VERSION) throw corrupt()
        val iterations = input.readInt()
        if (iterations <= 0) throw corrupt()
        val salt = ByteArray(readLength(input, MAX_SALT_LEN))
        input.readFully(salt)
        val iv = ByteArray(readLength(input, MAX_IV_LEN))
        input.readFully(iv)
        // v2 起才有密文长度；v1 的密文一直读到文件尾，无法反推是否被截断
        val declared = if (version >= 2) readLength(input, Int.MAX_VALUE) else null
        val cipherText = input.readBytes()
        if (cipherText.isEmpty()) throw corrupt()
        return Header(iterations, salt, iv, cipherText, declared)
    }

    private fun readLength(
        input: DataInputStream,
        max: Int,
    ): Int {
        val length = input.readInt()
        if (length <= 0 || length > max) throw corrupt()
        return length
    }

    private fun corrupt() = BackupException(BackupFailure.CORRUPT, "备份文件已损坏")

    private fun deriveKey(
        password: String,
        salt: ByteArray,
        iterations: Int,
    ): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS)
        val factory = SecretKeyFactory.getInstance(KEY_ALGORITHM)
        val encoded = factory.generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(encoded, "AES")
    }
}
