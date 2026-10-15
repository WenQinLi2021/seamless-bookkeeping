package com.bookkeeping.app.update

/**
 * 远端发布的一个版本。
 *
 * 版本比较用 [versionCode]（整数，单调递增）而不是版本名，避免 "1.10.0" 和 "1.9.0"
 * 这种字符串比较踩坑。[versionName] 只用来展示。
 */
data class RemoteRelease(
    val versionCode: Int,
    val versionName: String,
    val notes: String,
    /** APK 的下载地址（GitHub Release asset 的 API 地址，私有仓库需要带 token 访问）。 */
    val downloadUrl: String,
    val assetName: String,
    val sizeBytes: Long,
    /** 发布脚本写入的 SHA-256，用来确认下载完整。null 表示发布方没提供。 */
    val sha256: String?,
)

/** 检查更新的结果。 */
sealed interface UpdateCheckResult {
    /** 没配置更新源，无从检查。 */
    data object NotConfigured : UpdateCheckResult

    data class UpToDate(val currentVersionName: String) : UpdateCheckResult

    data class Available(val release: RemoteRelease) : UpdateCheckResult

    data class Failed(val message: String) : UpdateCheckResult
}

/** 下载与安装过程中的状态，直接驱动 UI。 */
sealed interface UpdateInstallState {
    data object Idle : UpdateInstallState

    data class Downloading(val downloadedBytes: Long, val totalBytes: Long) : UpdateInstallState {
        val percent: Int
            get() = if (totalBytes <= 0L) 0
            else ((downloadedBytes * 100) / totalBytes).toInt().coerceIn(0, 100)
    }

    data object Verifying : UpdateInstallState

    /** 校验通过、已落盘，等待（或正在）拉起系统安装器。 */
    data class ReadyToInstall(val apkPath: String) : UpdateInstallState

    /** 需要用户先去系统设置里允许「安装未知应用」。 */
    data class NeedInstallPermission(val apkPath: String) : UpdateInstallState

    data class Failed(val message: String) : UpdateInstallState
}

/** 更新源的配置。私有仓库需要 token 才能读 Release。 */
data class UpdateSource(
    val owner: String,
    val repo: String,
    val token: String,
) {
    val isConfigured: Boolean
        get() = owner.isNotBlank() && repo.isNotBlank() && token.isNotBlank()
}
