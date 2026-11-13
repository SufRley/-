package com.xxjz.ledger.feature.backup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xxjz.ledger.data.backup.BackupCodec
import com.xxjz.ledger.data.backup.BackupCrypto
import com.xxjz.ledger.data.backup.BackupException
import com.xxjz.ledger.data.backup.BackupFailure
import com.xxjz.ledger.data.backup.BackupPayload
import com.xxjz.ledger.data.backup.BackupPayloadBuilder
import com.xxjz.ledger.data.backup.BackupStats
import com.xxjz.ledger.data.datastore.SettingsDataStore
import com.xxjz.ledger.data.io.DocumentIo
import com.xxjz.ledger.data.local.db.AppDatabase
import com.xxjz.ledger.domain.usecase.RestoreBackupUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject

/** 密码强度：AC-69 要求至少 6 位。 */
const val MIN_PASSWORD_LENGTH = 6

/** 导出成功后的信息，用于展示「路径 / 大小 / 时间」+ 分享（AC-70）。 */
data class ExportedFile(
    val name: String,
    val uriString: String,
    val sizeBytes: Long,
    val createdAt: Long,
    val password: String,
) {
    val sizeLabel: String
        get() =
            when {
                sizeBytes < 1024 -> "$sizeBytes B"
                sizeBytes < 1024 * 1024 -> "%.1f KB".format(sizeBytes / 1024.0)
                else -> "%.2f MB".format(sizeBytes / 1024.0 / 1024.0)
            }

    val timeLabel: String
        get() =
            Instant
                .ofEpochMilli(createdAt)
                .atZone(ZoneId.systemDefault())
                .let { "%04d-%02d-%02d %02d:%02d".format(it.year, it.monthValue, it.dayOfMonth, it.hour, it.minute) }
}

data class BackupState(
    val password: String = "",
    val confirmPassword: String = "",
    val exporting: Boolean = false,
    val exported: ExportedFile? = null,
    /** 校验提示，只在不通过时出现。 */
    val passwordError: String? = null,
    val lastBackupAt: Long = 0,
    val reminderEnabled: Boolean = true,
) {
    val passwordValid: Boolean
        get() = password.length >= MIN_PASSWORD_LENGTH && password == confirmPassword
}

data class RestoreState(
    val picking: Boolean = false,
    val fileName: String = "",
    val uriString: String = "",
    val password: String = "",
    val decrypting: Boolean = false,
    val preview: BackupPayload? = null,
    val done: Boolean = false,
    val error: String? = null,
) {
    val stats: BackupStats? get() = preview?.stats
}

/**
 * P23 导出备份（AC-68 / AC-69 / AC-70）。
 *
 * 密码走两次校验：≥6 位 + 两次一致。密码只用于当场加密，不落盘、不进日志。
 */
@HiltViewModel
class BackupViewModel
    @Inject
    constructor(
        private val builder: BackupPayloadBuilder,
        private val codec: BackupCodec,
        private val settings: SettingsDataStore,
        private val io: DocumentIo,
    ) : ViewModel() {
        /** 写入调度器；测试里换成 Unconfined，避免真实 IO 线程与断言抢跑。 */
        internal var ioDispatcher: CoroutineDispatcher = Dispatchers.IO

        private val _state = MutableStateFlow(BackupState())
        val state: StateFlow<BackupState> = _state.asStateFlow()

        init {
            viewModelScope.launch {
                settings.settings.collect { s ->
                    _state.update {
                        it.copy(lastBackupAt = s.lastBackupAt, reminderEnabled = s.backupReminderEnabled)
                    }
                }
            }
        }

        fun setPassword(value: String) {
            _state.update { it.copy(password = value, passwordError = null) }
        }

        fun setConfirmPassword(value: String) {
            _state.update { it.copy(confirmPassword = value, passwordError = null) }
        }

        fun setReminderEnabled(enabled: Boolean) =
            viewModelScope.launch {
                settings.setBackupReminder(enabled)
            }

        /** 开始导出前的校验；返回是否通过。 */
        fun validate(): Boolean {
            val s = _state.value
            val error =
                when {
                    s.password.isBlank() -> "请设置备份密码"
                    s.password.length < MIN_PASSWORD_LENGTH -> "密码至少 $MIN_PASSWORD_LENGTH 位"
                    s.password != s.confirmPassword -> "两次输入的密码不一致"
                    else -> null
                }
            _state.update { it.copy(passwordError = error) }
            return error == null
        }

        /** 把加密后的备份写入用户选定的文件（SAF）。 */
        fun exportTo(uriString: String) =
            viewModelScope.launch {
                val s = _state.value
                if (!validate()) return@launch
                _state.update { it.copy(exporting = true) }
                val result =
                    runCatching {
                        val payload = builder.build(settings.current())
                        val bytes = codec.encode(payload, s.password)
                        withContext(ioDispatcher) {
                            io.openOutput(uriString).use { it.write(bytes) }
                        }
                        ExportedFile(
                            name = io.displayName(uriString).ifBlank { defaultFileName(payload.createdAt) },
                            uriString = uriString,
                            sizeBytes = bytes.size.toLong(),
                            createdAt = payload.createdAt,
                            password = s.password,
                        )
                    }
                result.fold(
                    onSuccess = { file ->
                        settings.markBackedUp(file.createdAt)
                        _state.update { it.copy(exporting = false, exported = file, passwordError = null) }
                    },
                    onFailure = { e ->
                        _state.update {
                            it.copy(exporting = false, passwordError = "导出失败：${e.message ?: "无法写入文件"}")
                        }
                    },
                )
            }

        fun clearExported() = _state.update { it.copy(exported = null, password = "", confirmPassword = "") }

        private fun defaultFileName(now: Long): String {
            val t = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault())
            return "一心记账备份_%04d%02d%02d_%02d%02d.xxjzbak".format(t.year, t.monthValue, t.dayOfMonth, t.hour, t.minute)
        }
    }

/**
 * P23 导入备份（AC-71 / AC-72 / AC-73 / AC-74）。
 *
 * 流程：选文件 → 输密码 → 解密并在内存里预览统计 → 用户确认覆盖 → 事务内替换。
 * 解密与版本校验的任何失败都翻译成人话，绝不让异常冒到界面（AC-72）。
 */
@HiltViewModel
class RestoreViewModel
    @Inject
    constructor(
        private val codec: BackupCodec,
        private val io: DocumentIo,
        private val restore: RestoreBackupUseCase,
    ) : ViewModel() {
        internal var ioDispatcher: CoroutineDispatcher = Dispatchers.IO

        private val _state = MutableStateFlow(RestoreState())
        val state: StateFlow<RestoreState> = _state.asStateFlow()

        /** 选完文件后登记，等用户输密码。 */
        fun selectFile(uriString: String) {
            _state.value =
                RestoreState(
                    fileName = runCatching { io.displayName(uriString) }.getOrDefault(""),
                    uriString = uriString,
                )
        }

        fun setPassword(value: String) = _state.update { it.copy(password = value, error = null) }

        fun clearError() = _state.update { it.copy(error = null) }

        /** 解密并做版本校验，成功后进入预览。 */
        fun decryptPreview() =
            viewModelScope.launch {
                val s = _state.value
                if (s.uriString.isBlank()) return@launch
                if (s.password.isBlank()) {
                    _state.update { it.copy(error = "请输入备份密码") }
                    return@launch
                }
                _state.update { it.copy(decrypting = true, error = null) }
                val result =
                    runCatching {
                        val bytes =
                            withContext(ioDispatcher) {
                                io.openInput(s.uriString).use { it.readBytes() }
                            }
                        if (!BackupCrypto.looksLikeBackup(bytes)) {
                            throw BackupException(BackupFailure.NOT_BACKUP, "这不是「一心记账」的备份文件")
                        }
                        codec.decode(bytes, s.password, currentSchemaVersion = AppDatabase.SCHEMA_VERSION)
                    }
                result.fold(
                    onSuccess = { payload -> _state.update { it.copy(decrypting = false, preview = payload) } },
                    onFailure = { e -> _state.update { it.copy(decrypting = false, error = friendly(e)) } },
                )
            }

        /** 用户确认后覆盖导入（AC-73）。 */
        fun confirmRestore() =
            viewModelScope.launch {
                val payload = _state.value.preview ?: return@launch
                _state.update { it.copy(decrypting = true) }
                val result = restore(payload)
                _state.update { st ->
                    when (result) {
                        is com.xxjz.ledger.core.common.AppResult.Success ->
                            st.copy(decrypting = false, done = true)
                        is com.xxjz.ledger.core.common.AppResult.Failure ->
                            st.copy(decrypting = false, error = result.error.message)
                    }
                }
            }

        fun backToPassword() = _state.update { it.copy(preview = null, password = "") }

        internal fun friendly(e: Throwable): String =
            when ((e as? BackupException)?.failure) {
                BackupFailure.NOT_BACKUP -> "这不是「一心记账」的备份文件"
                BackupFailure.WRONG_PASSWORD -> "密码错误，无法解密这份备份"
                BackupFailure.UNSUPPORTED_VERSION -> e.message ?: "备份版本不兼容"
                BackupFailure.CORRUPT -> "备份文件已损坏，无法导入"
                null -> "导入失败：${e.message ?: "未知错误"}"
            }
    }
