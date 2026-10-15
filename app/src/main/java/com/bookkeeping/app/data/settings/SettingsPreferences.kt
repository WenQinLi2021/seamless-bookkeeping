package com.bookkeeping.app.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.bookkeeping.app.update.UpdateSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton

// 顶层属性：app 全局只有一份 settings DataStore
private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings_prefs")

// 全局设置项：前台保活开关 + 应用更新源。
@Singleton
class SettingsPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun observeKeepAliveEnabled(): Flow<Boolean> =
        context.settingsDataStore.data.map { it[KEY_KEEP_ALIVE] ?: false }

    suspend fun setKeepAliveEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[KEY_KEEP_ALIVE] = enabled }
    }

    /**
     * 更新源。仓库默认指向本项目的私有仓库，用户只需要补一个能读 Release 的 token。
     *
     * 注意：token 以明文存在本机 DataStore 里。这是个人自用项目的取舍——私有仓库的
     * Release 必须带 token 才能下载，而本项目又没有任何后端可以代持凭据。
     * 建议只签发**仅对这一个仓库有只读权限**的 fine-grained token，且随时可在 GitHub 撤销。
     */
    fun observeUpdateSource(): Flow<UpdateSource> =
        context.settingsDataStore.data.map { preferences ->
            UpdateSource(
                owner = preferences[KEY_UPDATE_OWNER] ?: DEFAULT_UPDATE_OWNER,
                repo = preferences[KEY_UPDATE_REPO] ?: DEFAULT_UPDATE_REPO,
                token = preferences[KEY_UPDATE_TOKEN].orEmpty(),
            )
        }

    suspend fun setUpdateSource(owner: String, repo: String, token: String) {
        context.settingsDataStore.edit { preferences ->
            preferences[KEY_UPDATE_OWNER] = owner.trim().ifBlank { DEFAULT_UPDATE_OWNER }
            preferences[KEY_UPDATE_REPO] = repo.trim().ifBlank { DEFAULT_UPDATE_REPO }
            preferences[KEY_UPDATE_TOKEN] = token.trim()
        }
    }

    // 同步读快照值。在 MainActivity.onCreate 里只需要"当前值"决定是否启动 Service，
    // 不需要 observe；用 runBlocking 拿一下，DataStore 本地读很快不会卡 UI。
    fun keepAliveEnabledBlocking(): Boolean = runBlocking {
        context.settingsDataStore.data.first()[KEY_KEEP_ALIVE] ?: false
    }

    companion object {
        private val KEY_KEEP_ALIVE = booleanPreferencesKey("keep_alive_enabled")
        private val KEY_UPDATE_OWNER = stringPreferencesKey("update_owner")
        private val KEY_UPDATE_REPO = stringPreferencesKey("update_repo")
        private val KEY_UPDATE_TOKEN = stringPreferencesKey("update_token")

        const val DEFAULT_UPDATE_OWNER = "WenQinLi2021"
        const val DEFAULT_UPDATE_REPO = "seamless-bookkeeping"
    }
}
