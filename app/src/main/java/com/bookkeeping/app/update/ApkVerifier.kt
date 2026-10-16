package com.bookkeeping.app.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** APK 校验结果。 */
sealed interface ApkVerifyResult {
    data class Ok(val versionCode: Long, val versionName: String) : ApkVerifyResult

    data class Rejected(val message: String) : ApkVerifyResult
}

/**
 * 安装前的四道校验。
 *
 * 这是整条更新链路上唯一"能把恶意包放进来"的关口：更新源是我们自己维护的仓库，
 * 但网络链路、以及仓库本身都可能出问题，所以下载到本机之后还要再确认
 * ① 摘要对得上 ② 包名是同一个应用 ③ **签名和当前安装的一模一样** ④ 版本确实更高。
 *
 * 第 ③ 条尤其重要：Android 只允许同签名的 APK 覆盖安装，如果签名对不上，装上去的
 * 会是一个"同名不同人"的应用（用户数据也会丢）。宁可直接拒绝。
 */
@Singleton
class ApkVerifier @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    fun verify(apkFile: File, expectedSha256: String?, expectedVersionCode: Int): ApkVerifyResult {
        if (!apkFile.isFile || apkFile.length() <= 0L) {
            return ApkVerifyResult.Rejected("安装包文件不存在或为空，请重新下载")
        }

        if (!expectedSha256.isNullOrBlank()) {
            val actual = sha256Of(apkFile)
            if (!actual.equals(expectedSha256, ignoreCase = true)) {
                return ApkVerifyResult.Rejected(
                    "安装包摘要不匹配，下载可能不完整或已被篡改，已中止安装。\n" +
                        "（期望 ${expectedSha256.take(12)}… 实际 ${actual.take(12)}…）"
                )
            }
        }

        val archive = packageArchiveInfo(apkFile)
            ?: return ApkVerifyResult.Rejected("无法解析这个安装包，可能下载不完整")

        if (archive.packageName != context.packageName) {
            return ApkVerifyResult.Rejected(
                "安装包的包名是 ${archive.packageName}，和本应用（${context.packageName}）不是同一个应用，已中止"
            )
        }

        val installedDigests = signingDigests(installedPackageInfo())
        val archiveDigests = signingDigests(archive)
        if (installedDigests.isEmpty() || archiveDigests.isEmpty()) {
            return ApkVerifyResult.Rejected("读不到签名信息，无法确认来源安全，已中止安装")
        }
        if (installedDigests != archiveDigests) {
            return ApkVerifyResult.Rejected(
                "安装包签名和当前应用不一致，无法覆盖安装。\n" +
                    "这通常意味着它是在另一台电脑上构建的（调试签名不同）。" +
                    "请先卸载本应用再安装，或者用同一台机器的构建产物。"
            )
        }

        val versionCode = archive.longVersionCodeCompat()
        if (expectedVersionCode > 0 && versionCode != expectedVersionCode.toLong()) {
            return ApkVerifyResult.Rejected(
                "安装包的实际版本号是 $versionCode，与发布说明里的 $expectedVersionCode 不一致，已中止"
            )
        }

        return ApkVerifyResult.Ok(versionCode = versionCode, versionName = archive.versionName.orEmpty())
    }

    private fun packageArchiveInfo(apkFile: File): PackageInfo? = context.packageManager.getPackageArchiveInfo(
        apkFile.absolutePath,
        signingFlags(),
    )

    @Suppress("DEPRECATION")
    private fun installedPackageInfo(): PackageInfo =
        context.packageManager.getPackageInfo(context.packageName, signingFlags())

    private fun signingFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }

    @Suppress("DEPRECATION")
    private fun signingDigests(info: PackageInfo?): Set<String> {
        if (info == null) return emptySet()
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners
        } else {
            info.signatures
        }
        return signatures.orEmpty().mapTo(mutableSetOf()) { digestOf(it.toByteArray()) }
    }

    @Suppress("DEPRECATION")
    private fun PackageInfo.longVersionCodeCompat(): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) longVersionCode else versionCode.toLong()

    private fun digestOf(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().toHex()
    }

    private fun ByteArray.toHex(): String =
        joinToString(separator = "") { byte -> "%02x".format(byte) }
}
