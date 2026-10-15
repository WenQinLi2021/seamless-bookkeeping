package com.bookkeeping.app.update

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseJsonTest {

    private val digestInAsset = "sha256:" + "b".repeat(64)

    private fun sample(
        tag: String = "v1.2.0",
        body: String = "versionCode=3\nsha256=${"a".repeat(64)}\n\n修复了账单导入里的一处去重问题",
        assets: String = """
            [{"name":"app-debug.apk",
              "url":"https://api.github.com/repos/o/r/releases/assets/1",
              "size":1048576,
              "digest":"$digestInAsset"}]
        """.trimIndent(),
    ): JSONObject = JSONObject(
        """{"tag_name": ${JSONObject.quote(tag)}, "body": ${JSONObject.quote(body)}, "assets": $assets}"""
    )

    private fun parseOrFail(json: JSONObject): RemoteRelease {
        val parsed = ReleaseJson.parse(json)
        org.junit.Assert.assertNotNull("应该能解析出发布信息，实际返回了 null", parsed)
        return requireNotNull(parsed)
    }

    @Test
    fun `正常发布能解析出全部字段`() {
        val release = parseOrFail(sample())

        assertEquals(3, release.versionCode)
        assertEquals("1.2.0", release.versionName)
        assertEquals("app-debug.apk", release.assetName)
        assertEquals(1048576L, release.sizeBytes)
        assertEquals("https://api.github.com/repos/o/r/releases/assets/1", release.downloadUrl)
    }

    @Test
    fun `tag没有v前缀也能解析`() {
        assertEquals("1.2.0", parseOrFail(sample(tag = "1.2.0")).versionName)
    }

    @Test
    fun `版本号取自正文的versionCode而不是版本名`() {
        // 版本名顺序（1.2.0 < 1.10.0）和 versionCode 顺序未必一致，必须认后者
        val release = parseOrFail(sample(tag = "v1.10.0", body = "versionCode=12\n\n说明"))

        assertEquals(12, release.versionCode)
        assertEquals("1.10.0", release.versionName)
    }

    @Test
    fun `约定字段不会出现在给用户看的更新说明里`() {
        val release = parseOrFail(sample())

        assertTrue(release.notes.contains("修复了账单导入"))
        assertTrue(!release.notes.contains("versionCode"))
        assertTrue(!release.notes.contains("sha256"))
    }

    @Test
    fun `优先使用asset自带的摘要`() {
        val release = parseOrFail(sample())

        assertEquals("b".repeat(64), release.sha256)
    }

    @Test
    fun `asset没有摘要时回退到正文里的sha256`() {
        val assets = """[{"name":"app-debug.apk","url":"https://api.github.com/x","size":10}]"""

        val release = parseOrFail(sample(assets = assets))

        assertEquals("a".repeat(64), release.sha256)
    }

    @Test
    fun `两侧都没有摘要时sha256为空但不影响解析`() {
        val assets = """[{"name":"app-debug.apk","url":"https://api.github.com/x","size":10}]"""

        val release = parseOrFail(sample(body = "versionCode=3\n\n说明", assets = assets))

        assertNull(release.sha256)
        assertEquals(3, release.versionCode)
    }

    @Test
    fun `缺少versionCode时拒绝解析而不是猜`() {
        assertNull(ReleaseJson.parse(sample(body = "这次忘了写版本号")))
    }

    @Test
    fun `没有apk附件时拒绝解析`() {
        val assets = """[{"name":"source.zip","url":"https://api.github.com/x","size":10}]"""

        assertNull(ReleaseJson.parse(sample(assets = assets)))
    }

    @Test
    fun `附件为空时拒绝解析`() {
        assertNull(ReleaseJson.parse(sample(assets = "[]")))
    }

    @Test
    fun `apk不是第一个附件时也能找到`() {
        val assets = """
            [{"name":"source.zip","url":"https://api.github.com/zip","size":1},
             {"name":"app-release.APK","url":"https://api.github.com/apk","size":2}]
        """.trimIndent()

        val release = parseOrFail(sample(assets = assets))

        assertEquals("app-release.APK", release.assetName)
        assertEquals("https://api.github.com/apk", release.downloadUrl)
    }
}
