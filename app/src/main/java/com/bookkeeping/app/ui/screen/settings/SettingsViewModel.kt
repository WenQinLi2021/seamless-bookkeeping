package com.bookkeeping.app.ui.screen.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bookkeeping.app.data.settings.SettingsPreferences
import com.bookkeeping.app.notification.KeepAliveService
import com.bookkeeping.app.update.AppUpdater
import com.bookkeeping.app.update.RemoteRelease
import com.bookkeeping.app.update.StartupUpdateCheck
import com.bookkeeping.app.update.UpdateCheckResult
import com.bookkeeping.app.update.UpdateInstallState
import com.bookkeeping.app.update.UpdateSource
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 更新卡片要展示的状态。 */
data class UpdateUiState(
    val checking: Boolean = false,
    /** 检查到的新版本；null 表示没有可用更新。 */
    val latest: RemoteRelease? = null,
    /** 「已是最新」「令牌没配」这类一次性提示。 */
    val checkMessage: String? = null,
    val install: UpdateInstallState = UpdateInstallState.Idle,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val prefs: SettingsPreferences,
    private val updater: AppUpdater,
    private val startupUpdateCheck: StartupUpdateCheck,
) : ViewModel() {

    val keepAliveEnabled: StateFlow<Boolean> = prefs.observeKeepAliveEnabled()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val updateSource: StateFlow<UpdateSource> = prefs.observeUpdateSource()
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            UpdateSource(
                owner = SettingsPreferences.DEFAULT_UPDATE_OWNER,
                repo = SettingsPreferences.DEFAULT_UPDATE_REPO,
                token = "",
            ),
        )

    private val _updateState = MutableStateFlow(UpdateUiState())
    val updateState: StateFlow<UpdateUiState> = _updateState.asStateFlow()

    val currentVersionName: String get() = updater.currentVersionName
    val currentVersionCode: Long get() = updater.currentVersionCode

    init {
        // 应用启动时做过一次静默检查，结果放在 StartupUpdateCheck 里。
        // 用户一旦打开设置页，就直接把那个结果显示出来，不用再点一次「检查更新」。
        viewModelScope.launch {
            startupUpdateCheck.foundRelease.collect { release ->
                if (release != null && _updateState.value.latest == null) {
                    _updateState.update { it.copy(latest = release) }
                }
            }
        }
    }

    fun setKeepAlive(enabled: Boolean) {
        viewModelScope.launch {
            prefs.setKeepAliveEnabled(enabled)
            if (enabled) KeepAliveService.start(appContext) else KeepAliveService.stop(appContext)
        }
    }

    fun checkForUpdate() {
        if (_updateState.value.checking) return
        _updateState.update {
            it.copy(checking = true, checkMessage = null, latest = null, install = UpdateInstallState.Idle)
        }
        viewModelScope.launch {
            val next = when (val result = updater.checkForUpdate()) {
                UpdateCheckResult.NotConfigured -> UpdateUiState(
                    checkMessage = "还没有配置更新源。请在下面填入能读取该私有仓库的访问令牌。"
                )
                is UpdateCheckResult.UpToDate -> UpdateUiState(
                    checkMessage = "已经是最新版本（${result.currentVersionName}）"
                )
                is UpdateCheckResult.Available ->
                    UpdateUiState(latest = result.release, checkMessage = null)
                is UpdateCheckResult.Failed -> UpdateUiState(checkMessage = result.message)
            }
            _updateState.value = next
        }
    }

    /** 下载 → 校验 → 拉起系统安装器（校验不通过就停在校验失败上，不会拉起）。 */
    fun downloadUpdate() {
        val release = _updateState.value.latest ?: return
        if (_updateState.value.install is UpdateInstallState.Downloading) return
        viewModelScope.launch {
            updater.downloadAndPrepare(release) { state ->
                _updateState.update { it.copy(install = state) }
            }
            val state = _updateState.value.install
            if (state is UpdateInstallState.ReadyToInstall) {
                triggerInstall(state.apkPath)
            }
        }
    }

    /** 已经从系统设置里打开「安装未知应用」后回来，重试安装。 */
    fun retryInstall() {
        val path = (_updateState.value.install as? UpdateInstallState.NeedInstallPermission)?.apkPath
            ?: return
        triggerInstall(path)
    }

    fun openInstallPermission() {
        updater.openInstallPermissionSettings()
    }

    fun dismissUpdateMessage() {
        _updateState.update { it.copy(checkMessage = null) }
    }

    fun resetInstallState() {
        _updateState.update { it.copy(install = UpdateInstallState.Idle) }
    }

    fun saveUpdateSource(owner: String, repo: String, token: String) {
        viewModelScope.launch {
            prefs.setUpdateSource(owner, repo, token)
            _updateState.value = UpdateUiState(checkMessage = "更新源已保存，可以点「检查更新」了")
        }
    }

    private fun triggerInstall(apkPath: String) {
        if (updater.install(apkPath)) {
            // 已经交给系统安装器了，后续由用户在系统弹窗里决定。
            // 顺手清掉启动时的检查结果，避免下次进来又提示同一个版本。
            startupUpdateCheck.clear()
            _updateState.update { it.copy(install = UpdateInstallState.Idle) }
        } else {
            _updateState.update { it.copy(install = UpdateInstallState.NeedInstallPermission(apkPath)) }
        }
    }
}
