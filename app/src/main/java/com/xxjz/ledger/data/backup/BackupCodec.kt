package com.xxjz.ledger.data.backup

import com.xxjz.ledger.core.common.Logger
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 备份编解码：`BackupPayload --JSON--> GZIP --> AES/GCM`。
 *
 * 版本策略（DEV_PLAN M8 关键技术点）：
 * - 格式版本（`payloadVersion`）高于本机支持 → 提示升级 App；
 * - 数据库版本（`schemaVersion`）高于本机支持 → 提示备份来自更新版本；
 * - 低于最低支持版本 → 提示「版本过旧，无法导入」。
 *
 * 三种情况都抛 [BackupException]，不抛序列化原始异常，UI 只需处理一种类型。
 */
@Singleton
class BackupCodec
    @Inject
    constructor() {
        private val tag = "BackupCodec"

        private val json =
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
                isLenient = false
            }

        /** 组装备份文件。 */
        fun encode(
            payload: BackupPayload,
            password: String,
        ): ByteArray {
            val plain = json.encodeToString(BackupPayload.serializer(), payload).toByteArray(Charsets.UTF_8)
            return BackupCrypto.encrypt(BackupCrypto.gzip(plain), password)
        }

        /**
         * 解密并解析备份文件。
         *
         * @param currentSchemaVersion 本机 Room 版本
         * @param minSchemaVersion 仍可导入的最低版本
         */
        fun decode(
            file: ByteArray,
            password: String,
            currentSchemaVersion: Int,
            minSchemaVersion: Int = 1,
        ): BackupPayload {
            val plain = BackupCrypto.gunzip(BackupCrypto.decrypt(file, password))
            val payload =
                try {
                    json.decodeFromString(BackupPayload.serializer(), plain.toString(Charsets.UTF_8))
                } catch (e: Exception) {
                    // 解析原始异常要记下来：是字段缺失还是类型不符，排查时差别很大
                    Logger.w(tag, "备份 JSON 解析失败", e)
                    throw BackupException(BackupFailure.CORRUPT, "备份内容无法解析，文件可能已损坏")
                }
            if (payload.payloadVersion > BackupPayload.CURRENT_PAYLOAD_VERSION) {
                throw BackupException(
                    BackupFailure.UNSUPPORTED_VERSION,
                    "备份来自更新版本的 App，请升级后再导入",
                )
            }
            if (payload.payloadVersion < BackupPayload.MIN_SUPPORTED_PAYLOAD_VERSION) {
                throw BackupException(BackupFailure.UNSUPPORTED_VERSION, "备份版本过旧，无法导入")
            }
            if (payload.schemaVersion > currentSchemaVersion) {
                throw BackupException(
                    BackupFailure.UNSUPPORTED_VERSION,
                    "备份来自更新版本的 App（数据 v${payload.schemaVersion}），请升级后再导入",
                )
            }
            if (payload.schemaVersion < minSchemaVersion) {
                throw BackupException(
                    BackupFailure.UNSUPPORTED_VERSION,
                    "备份版本过旧（数据 v${payload.schemaVersion}），无法导入",
                )
            }
            return payload
        }
    }
