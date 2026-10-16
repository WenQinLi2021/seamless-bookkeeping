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
     * 更新源。默认指向本项目自己的仓库，而这个仓库是**公开**的，所以手机端什么都不用填
     * 就能检查更新——零配置。
     *
     * [token] 是给"万一改回私有仓库"留的后路：把仓库设为私有后，在这里填一个只读令牌
     * 即可，其余逻辑不用动。注意令牌以明文存在本机 DataStore 里（本项目没有后端可以
     * 代持凭据），所以只建议签发**仅对该仓库只读**的 fine-grained token，随时可撤销。
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
