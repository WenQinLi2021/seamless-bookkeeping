package com.bookkeeping.app.importer

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.exception.ZipException
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** 压缩包读取结果。 */
sealed interface ArchiveReadResult {
    data class Text(val text: String, val sourceName: String) : ArchiveReadResult

    /** 压缩包有密码，需要 UI 追问一次解压密码。 */
    data object NeedsPassword : ArchiveReadResult

    /** 用户填了密码但解不开（填错了）。UI 应该让他原地重填，而不是重新选文件。 */
    data object WrongPassword : ArchiveReadResult

    data class Failure(val message: String) : ArchiveReadResult
}

/**
 * 读取用户选择的账单文件。
 *
 * 微信/支付宝的账单通过邮箱下发时是**带密码的 zip**（微信密码在支付通知里给出，
 * 支付宝是实名身份证后 6 位），所以这里必须支持密码，否则用户拿到文件也导不进来。
 * 用 zip4j 而不是 java.util.zip：JDK 自带的 ZipInputStream 无法解 ZipCrypto/AES 加密条目。
 */
@Singleton
class BillArchiveReader @Inject constructor(
    @ApplicationContext private val context: Context,
) : BillReader {

    override fun read(bytes: ByteArray, fileName: String?, password: String?): ArchiveReadResult {
        if (!looksLikeZip(bytes, fileName)) {
            return ArchiveReadResult.Text(BillTextDecoder.decode(bytes), fileName ?: "账单文件")
        }
        if (bytes.size > MAX_ARCHIVE_BYTES) {
            return ArchiveReadResult.Failure("压缩包过大（超过 64 MB），请确认选择的是账单文件")
        }

        val temp = File(context.cacheDir, "bill-import-${System.nanoTime()}.zip")
        return try {
            temp.writeBytes(bytes)
            readZip(temp, fileName, password)
        } catch (e: Exception) {
            ArchiveReadResult.Failure("读取压缩包失败：${e.message ?: e.javaClass.simpleName}")
        } finally {
            temp.delete()
        }
    }

    private fun readZip(temp: File, fileName: String?, password: String?): ArchiveReadResult {
        val zip = try {
            ZipFile(temp)
        } catch (e: ZipException) {
            return ArchiveReadResult.Failure("不是有效的压缩包：${e.message ?: "格式错误"}")
        }

        val headers = runCatching { zip.fileHeaders.orEmpty() }
            .getOrElse { return ArchiveReadResult.Failure("无法列出压缩包内容") }
            .filter { !it.isDirectory }
        if (headers.isEmpty()) return ArchiveReadResult.Failure("压缩包内没有文件")

        val encrypted = runCatching { zip.isEncrypted }.getOrDefault(false)
        if (encrypted && password.isNullOrEmpty()) return ArchiveReadResult.NeedsPassword
        if (encrypted) zip.setPassword(password!!.toCharArray())

        // 优先挑 csv；部分导出会同时带一份说明文件
        val entry = headers.firstOrNull { it.fileName.endsWith(".csv", ignoreCase = true) }
            ?: headers.first()

        val content = try {
            zip.getInputStream(entry).use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                var total = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > MAX_CONTENT_BYTES) {
                        return ArchiveReadResult.Failure("账单文件过大（超过 32 MB），已中止")
                    }
                    out.write(buffer, 0, count)
                }
                out.toByteArray()
            }
        } catch (e: ZipException) {
            // 密码错、文件损坏都会走到这里，靠"这个包是否加密"区分：
            // 加密包解不开基本就是密码错，让用户原地重填比给个笼统报错有用。
            return if (encrypted) {
                ArchiveReadResult.WrongPassword
            } else {
                ArchiveReadResult.Failure("解压失败：${e.message ?: "文件损坏"}")
            }
        }

        return ArchiveReadResult.Text(BillTextDecoder.decode(content), entry.fileName ?: fileName ?: "账单文件")
    }

    /** 通过文件头和文件名判断是不是 zip。 */
    private fun looksLikeZip(bytes: ByteArray, fileName: String?): Boolean {
        val zipMagic = bytes.size >= 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte() &&
            (bytes[2].toInt() and 0xFF) == 3 && (bytes[3].toInt() and 0xFF) == 4
        val byName = fileName?.endsWith(".zip", ignoreCase = true) == true
        return zipMagic || byName
    }

    private companion object {
        const val MAX_ARCHIVE_BYTES = 64L * 1024 * 1024
        const val MAX_CONTENT_BYTES = 32L * 1024 * 1024
    }
}
