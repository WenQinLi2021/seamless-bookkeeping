package com.bookkeeping.app.importer

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * 账单文本解码。
 *
 * 现实情况：
 * - 微信导出的 CSV 是 UTF-8（带 BOM）
 * - 支付宝导出的 CSV 是 GBK / GB18030
 * 如果一律按 UTF-8 读，支付宝账单里的中文会变成乱码，导致表头匹配失败。
 */
object BillTextDecoder {

    private val GB18030: Charset = Charset.forName("GB18030")

    fun decode(bytes: ByteArray): String {
        // 1. 有 BOM 就按 BOM 指定的编码读，最可靠
        if (startsWith(bytes, 0xEF, 0xBB, 0xBF)) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (startsWith(bytes, 0xFF, 0xFE)) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        if (startsWith(bytes, 0xFE, 0xFF)) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }

        // 2. 没有 BOM：先严格按 UTF-8 试，失败说明是 GBK 系
        val strictUtf8 = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            strictUtf8.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            // GB18030 是 GBK 的超集，能覆盖两者
            String(bytes, GB18030)
        }
    }

    private fun startsWith(bytes: ByteArray, vararg prefix: Int): Boolean {
        if (bytes.size < prefix.size) return false
        return prefix.indices.all { bytes[it].toInt() and 0xFF == prefix[it] }
    }
}
