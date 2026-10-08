package io.github.howard20181.hyperos.fcmlive

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

internal object AllowlistDocument {
    const val MAX_BYTES = 256 * 1024
    fun read(input: InputStream): Set<String> {
        val bytes = input.readNBytes(MAX_BYTES + 1)
        require(bytes.size <= MAX_BYTES) { "名单文件超过 256 KiB" }
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString().removePrefix(0xFEFF.toChar().toString())
        val packages = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith('#') }.toSet()
        val pattern = Regex("[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*")
        require(packages.all { pattern.matches(it) }) { "名单中包含无效包名" }
        return packages
    }
}
