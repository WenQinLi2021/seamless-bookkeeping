package com.bookkeeping.app.update

import org.json.JSONObject

/**
 * 解析 GitHub Releases API 返回的 JSON。
 *
 * 单独抽出来是为了能在纯 JVM 单测里跑：这段逻辑一旦出错，后果是"永远提示有新版本"
 * 或者"永远检查不到更新"，而且很难在真机上定位。
 *
 * 约定（由 `scripts/publish_release.py` 保证）：
 * - tag 形如 `v1.2.0`
 * - 正文里含 `versionCode=<整数>`，客户端用它做单调比较
 * - 正文里可含 `sha256=<64 位十六进制>`；asset 自带的 digest 优先
 */
internal object ReleaseJson {

    private val VERSION_CODE_PATTERN = Regex("""versionCode\s*=\s*(\d+)""")
    private val SHA256_PATTERN = Regex("""sha256\s*=\s*([0-9a-fA-F]{64})""")

    fun parse(json: JSONObject): RemoteRelease? {
        val tag = json.optString("tag_name").removePrefix("v").removePrefix("V")
        val body = json.optString("body")

        // versionCode 是版本比较的唯一依据，解析不到就宁可当成"没有可用的发布"，
        // 也不要猜——猜错的代价是给用户装一个更旧的包。
        val versionCode = VERSION_CODE_PATTERN.find(body)
            ?.groupValues?.get(1)?.toIntOrNull()
            ?: return null

        val assets = json.optJSONArray("assets") ?: return null
        var downloadUrl: String? = null
        var assetName = ""
        var sizeBytes = 0L
        var digest: String? = null
        for (index in 0 until assets.length()) {
            val asset = assets.optJSONObject(index) ?: continue
            val name = asset.optString("name")
            if (!name.endsWith(".apk", ignoreCase = true)) continue
            downloadUrl = asset.optString("url")
            assetName = name
            sizeBytes = asset.optLong("size")
            digest = asset.optString("digest")
                .takeIf { it.startsWith("sha256:") }
                ?.removePrefix("sha256:")
            break
        }
        if (downloadUrl.isNullOrBlank()) return null

        val sha256 = digest
            ?: SHA256_PATTERN.find(body)?.groupValues?.get(1)?.lowercase()

        return RemoteRelease(
            versionCode = versionCode,
            versionName = tag.ifBlank { "versionCode $versionCode" },
            // 前两行是给机器看的约定字段，不要拿去展示
            notes = body.lineSequence()
                .filterNot { it.startsWith("versionCode") || it.startsWith("sha256") }
                .joinToString("\n")
                .trim(),
            downloadUrl = downloadUrl,
            assetName = assetName,
            sizeBytes = sizeBytes,
            sha256 = sha256,
        )
    }
}
