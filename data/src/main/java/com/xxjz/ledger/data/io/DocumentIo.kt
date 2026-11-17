package com.xxjz.ledger.data.io

import android.content.ContentResolver
import android.net.Uri
import java.io.InputStream
import java.io.OutputStream

/**
 * 文件读写的薄封装，便于 ViewModel 在单测里替换成内存实现。
 *
 * 只依赖 [ContentResolver]，不直接碰 `Activity`：SAF 返回的 `content://` Uri 全部经此通道读写，
 * 这样备份/导出/导入三条链路的行为在一个地方控制（例如统一关流、统一捕获异常）。
 */
interface DocumentIo {
    fun openInput(uri: String): InputStream

    fun openOutput(uri: String): OutputStream

    /** 显示用的文件名；取不到时退回 Uri 末段。 */
    fun displayName(uri: String): String
}

/** 基于 `ContentResolver` 的实现。 */
class ContentResolverDocumentIo(
    private val resolver: ContentResolver,
) : DocumentIo {
    override fun openInput(uri: String): InputStream = resolver.openInputStream(Uri.parse(uri)) ?: error("无法读取所选文件")

    override fun openOutput(uri: String): OutputStream = resolver.openOutputStream(Uri.parse(uri)) ?: error("无法写入所选位置")

    override fun displayName(uri: String): String {
        val parsed = Uri.parse(uri)
        return runCatching {
            resolver
                .query(parsed, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
        }.getOrNull() ?: parsed.lastPathSegment.orEmpty()
    }
}
