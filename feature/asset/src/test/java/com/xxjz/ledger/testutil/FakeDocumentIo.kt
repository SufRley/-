package com.xxjz.ledger.testutil

/** SAF 文件通道的内存实现：用 Uri 当 key 存字节，测试里可以直接读出写了什么。 */
class FakeDocumentIo : com.xxjz.ledger.data.io.DocumentIo {
    val files = mutableMapOf<String, ByteArray>()
    var failOnWrite = false
    var displayNameOverride = ""

    override fun openInput(uri: String): java.io.InputStream = java.io.ByteArrayInputStream(files[uri] ?: error("文件不存在"))

    override fun openOutput(uri: String): java.io.OutputStream {
        if (failOnWrite) error("模拟写入失败")
        return object : java.io.ByteArrayOutputStream() {
            override fun close() {
                super.close()
                files[uri] = toByteArray()
            }
        }
    }

    override fun displayName(uri: String): String = displayNameOverride.ifBlank { uri.substringAfterLast('/') }
}
