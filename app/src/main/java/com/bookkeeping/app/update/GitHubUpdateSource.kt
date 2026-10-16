package com.bookkeeping.app.update

import android.util.Log
import com.bookkeeping.app.BuildConfig
import com.bookkeeping.app.data.settings.SettingsPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 从 GitHub Releases 读最新版本。
 *
 * 为什么用 Release 而不是自己维护一个 version.json：Release 是 GitHub 自带的发布通道，
 * 打 tag、传 APK、写更新说明一次搞定，不用另外找地方托管安装包。
 *
 * 仓库默认是公开的，所以这里**不带令牌**也能工作——手机端零配置。令牌是留给
 * "万一改回私有仓库"的场景：填了就带上，没填就当公开仓库访问。
 *
 * 约定（由 `scripts/publish_release.py` 保证）：
 * - tag 形如 `v1.2.0`
 * - Release 正文里含一行 `versionCode=3`，客户端用它做单调递增的版本比较
 * - 正文里可选含 `sha256=<摘要>`，与 asset 自带的 digest 二者取其一做完整性校验
 */
@Singleton
class GitHubUpdateSource @Inject constructor(
    private val settings: SettingsPreferences,
) {

    suspend fun fetchLatest(): UpdateCheckResult {
        val source = settings.observeUpdateSource().first()
        if (!source.isConfigured) return UpdateCheckResult.NotConfigured

        return withContext(Dispatchers.IO) {
            try {
                val json = getJson(
                    url = "$API_BASE/repos/${source.owner}/${source.repo}/releases/latest",
                    token = source.token,
                )
                val release = ReleaseJson.parse(json)
                    ?: return@withContext UpdateCheckResult.Failed(
                        "这个版本没有附带安装包，或者发布信息里缺少 versionCode。\n" +
                            "请用 scripts/publish_release.py 重新发布一次。"
                    )
                UpdateCheckResult.Available(release)
            } catch (e: HttpStatusException) {
                UpdateCheckResult.Failed(e.userMessage)
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) Log.w(TAG, "检查更新失败", e)
                UpdateCheckResult.Failed("检查更新失败：${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private fun getJson(url: String, token: String): JSONObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", API_VERSION)
            setRequestProperty("User-Agent", USER_AGENT)
            if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer $token")
        }
        try {
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                throw HttpStatusException(code, connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty())
            }
            val text = connection.inputStream.bufferedReader().use { it.readText() }
            return JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }

    /** 把 HTTP 状态码翻译成用户能看懂的话，而不是丢一个 404 给他。 */
    private class HttpStatusException(val code: Int, val body: String) : Exception("HTTP $code") {
        val userMessage: String
            get() = when (code) {
                HttpURLConnection.HTTP_UNAUTHORIZED ->
                    "访问令牌无效或已过期。请重新生成一个只读令牌（公开仓库可以直接把令牌留空）。"
                HttpURLConnection.HTTP_FORBIDDEN ->
                    "GitHub 拒绝了这次请求，通常是接口限流——匿名访问每小时额度较低，稍后再试。\n" +
                        "如果仓库是私有的，也可能是令牌权限不足：" +
                        "请确认 token 至少有该仓库的「只读 Contents / Releases」权限。"
                HttpURLConnection.HTTP_NOT_FOUND ->
                    "找不到仓库或还没有发布过版本。\n" +
                        "请确认仓库名填写正确，且已经在 GitHub 上发布过 Release。\n" +
                        "注意：如果是私有仓库且没填令牌，GitHub 也会返回 404，" +
                        "这种情况下需要在「更新源设置」里补一个只读访问令牌。"
                else -> "GitHub 返回了 HTTP $code。${body.take(160)}"
            }
    }

    private companion object {
        const val API_BASE = "https://api.github.com"
        const val API_VERSION = "2022-11-28"
        const val USER_AGENT = "seamless-bookkeeping-updater"
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000
        const val TAG = "UpdateSource"
    }
}
