package com.bookkeeping.app.update

import com.bookkeeping.app.BuildConfig
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 更新流程的编排：检查 → 下载 → 校验 → 拉起安装。
 *
 * 单独抽出来是为了让 ViewModel 只管 UI 状态，而"什么时候该拒绝一个包"这种判断
 * 集中在一处。
 */
@Singleton
class AppUpdater @Inject constructor(
    private val source: GitHubUpdateSource,
    private val downloader: ApkDownloader,
    private val verifier: ApkVerifier,
    private val installer: ApkInstaller,
) {

    val currentVersionName: String get() = BuildConfig.VERSION_NAME

    val currentVersionCode: Long get() = BuildConfig.VERSION_CODE.toLong()

    suspend fun checkForUpdate(): UpdateCheckResult {
        val result = source.fetchLatest()
        if (result is UpdateCheckResult.Available &&
            result.release.versionCode.toLong() <= currentVersionCode
        ) {
            return UpdateCheckResult.UpToDate(currentVersionName)
        }
        return result
    }

    /**
     * 下载并校验，然后按当前是否已获「安装未知应用」权限决定是直接拉起安装器、
     * 还是先让用户去授权。
     *
     * [onState] 可能会从 IO 线程回调（下载进度），接收方需要能容忍这一点；
     * MutableStateFlow 的赋值本身是线程安全的。
     */
    suspend fun downloadAndPrepare(
        release: RemoteRelease,
        onState: (UpdateInstallState) -> Unit,
    ) {
        try {
            onState(UpdateInstallState.Downloading(0L, release.sizeBytes))
            val file = downloader.download(release) { downloaded, total ->
                onState(UpdateInstallState.Downloading(downloaded, total))
            }

            onState(UpdateInstallState.Verifying)
            when (val verified = verifier.verify(file, release.sha256, release.versionCode)) {
                is ApkVerifyResult.Rejected -> onState(UpdateInstallState.Failed(verified.message))
                is ApkVerifyResult.Ok -> onState(UpdateInstallState.ReadyToInstall(file.absolutePath))
            }
        } catch (e: IOException) {
            onState(UpdateInstallState.Failed("下载失败：${e.message ?: "网络异常"}"))
        } catch (e: Exception) {
            onState(UpdateInstallState.Failed("更新失败：${e.message ?: e.javaClass.simpleName}"))
        }
    }

    /**
     * 真正拉起系统安装器。
     *
     * @return true 表示已拉起；false 表示缺少权限或系统里没有安装器组件。
     */
    fun install(apkPath: String): Boolean {
        val file = File(apkPath)
        if (!file.isFile) return false
        if (!installer.canInstallPackages()) return false
        return installer.install(file)
    }

    fun canInstallPackages(): Boolean = installer.canInstallPackages()

    fun openInstallPermissionSettings() = installer.openUnknownSourcesSettings()
}
