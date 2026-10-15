package com.bookkeeping.app.update

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 应用启动时静默检查一次更新，把结果留着给设置页展示。
 *
 * 刻意做得很薄：不弹窗、不打断、失败也不提示（没配 token、没网都是正常情况）。
 * 设置页打开时会订阅这里，如果启动时发现了新版本，用户一进去就能看到。
 * 这样既满足"打开就检查"，又不会在每次冷启动都蹦一个弹窗出来。
 */
@Singleton
class StartupUpdateCheck @Inject constructor(
    private val updater: AppUpdater,
) {

    private val _foundRelease = MutableStateFlow<RemoteRelease?>(null)

    /** 启动时检查到的新版本；null 表示没有 / 还没检查出结果。 */
    val foundRelease: StateFlow<RemoteRelease?> = _foundRelease.asStateFlow()

    suspend fun runQuietly() {
        // 已经检查过了就不重复打扰网络
        if (_foundRelease.value != null) return
        val result = runCatching { updater.checkForUpdate() }.getOrNull()
        if (result is UpdateCheckResult.Available) {
            _foundRelease.value = result.release
        }
    }

    /** 用户手动检查并安装完后，清掉这个提示。 */
    fun clear() {
        _foundRelease.value = null
    }
}
