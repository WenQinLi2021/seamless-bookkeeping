package com.bookkeeping.app.update

import android.content.Context
import com.bookkeeping.app.data.settings.SettingsPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 把 APK 下载到应用私有目录。
 *
 * 私有仓库的 Release asset 必须带 token 访问，所以下载走的是 GitHub 的 asset API
 * 地址（`/releases/assets/{id}`）而不是页面上的下载链接。这个接口会先返回 302
 * 跳到 GitHub 的预签名地址（`release-assets.githubusercontent.com`），所以这里自己
 * 处理重定向——顺便可以保证每一跳都是 HTTPS（避免被中间人换掉安装包），
 * 并把令牌限制在 api.github.com 这一个主机上。
 */
@Singleton
class ApkDownloader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsPreferences,
) {

    suspend fun download(
        release: RemoteRelease,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val token = settings.observeUpdateSource().first().token
        if (!release.downloadUrl.startsWith("https://")) {
            throw IOException("更新地址不是 HTTPS，已拒绝下载")
        }

        val targetDir = File(context.filesDir, "updates").apply { mkdirs() }
        // 覆盖式下载：反正每次都是新版本，留着旧包只会占空间
        targetDir.listFiles()?.forEach { it.delete() }
        val target = File(targetDir, "update-${release.versionCode}.apk")

        val connection = openWithRedirects(release.downloadUrl, token)
        try {
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                throw IOException("下载失败，服务器返回 HTTP $code")
            }
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: release.sizeBytes
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var downloaded = 0L
                    var lastReported = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        downloaded += count
                        // 每 256 KB 报一次，避免刷爆 UI
                        if (downloaded - lastReported >= 256 * 1024) {
                            lastReported = downloaded
                            onProgress(downloaded, total)
                        }
                    }
                    onProgress(downloaded, total)
                }
            }
        } finally {
            connection.disconnect()
        }

        if (target.length() <= 0L) throw IOException("下载得到的文件是空的")
        target
    }

    private fun openWithRedirects(url: String, token: String): HttpURLConnection {
        var current = URL(url)
        var hops = 0
        while (true) {
            // 令牌只发给 GitHub 的 API 主机。302 之后拿到的是带签名的临时地址
            // （release-assets.githubusercontent.com），它自己带鉴权信息，不需要令牌；
            // 继续携带只会把凭据暴露给一个我们没打算信任的主机。
            val sendToken = token.isNotBlank() && current.host == API_HOST
            val connection = (current.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Accept", "application/octet-stream")
                setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                setRequestProperty("User-Agent", "seamless-bookkeeping-updater")
                if (sendToken) setRequestProperty("Authorization", "Bearer $token")
            }

            val code = connection.responseCode
            if (code in 300..399) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                if (location.isNullOrBlank() || hops >= MAX_REDIRECTS) {
                    throw IOException("下载地址重定向异常")
                }
                val next = URL(current, location)
                if (next.protocol != "https") {
                    throw IOException("重定向到了非 HTTPS 地址，已中止（可能是网络被篡改）")
                }
                current = next
                hops++
                continue
            }
            return connection
        }
    }

    private companion object {
        const val API_HOST = "api.github.com"
        const val CONNECT_TIMEOUT_MS = 20_000
        const val READ_TIMEOUT_MS = 15 * 60 * 1000
        const val MAX_REDIRECTS = 5
    }
}
