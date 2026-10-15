package com.bookkeeping.app.ui.screen.importer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bookkeeping.app.importer.BillImportOutcome
import com.bookkeeping.app.importer.BillImportSummary
import com.bookkeeping.app.importer.BillImporter
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject

/** 导入页当前处于哪一步。用密封接口而不是若干布尔量，避免出现"又在跑又报错"这种非法组合。 */
sealed interface ImportPhase {
    data object Idle : ImportPhase

    data object Running : ImportPhase

    /** 需要解压密码。[wrongAttempt] = true 表示上次填错了，UI 要给出"密码不对"的提示。 */
    data class AskPassword(val fileName: String, val wrongAttempt: Boolean) : ImportPhase

    data class Done(val summary: BillImportSummary) : ImportPhase

    data class Failed(val message: String) : ImportPhase
}

data class BillImportUiState(val phase: ImportPhase = ImportPhase.Idle)

@HiltViewModel
class BillImportViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val importer: BillImporter,
) : ViewModel() {

    private val _uiState = MutableStateFlow(BillImportUiState())
    val uiState: StateFlow<BillImportUiState> = _uiState.asStateFlow()

    /**
     * 当前这次选择的文件。密码填错时要能原样重试——让用户重新走一遍系统文件选择器
     * 是很糟糕的体验（微信导出的 zip 往往在"最近文件"里翻半天）。
     *
     * 只放在内存里，不落盘：账单压缩包的密码通常是身份证后 6 位，不该留在本地存储。
     */
    private var pickedFile: PickedFile? = null

    /** 系统文件选择器返回。uri 为 null 表示用户中途取消了，什么都不做。 */
    fun onFilePicked(uri: Uri?) {
        if (uri == null) return
        _uiState.value = BillImportUiState(ImportPhase.Running)
        viewModelScope.launch {
            val file = withContext(Dispatchers.IO) { readPickedFile(uri) }
            if (file == null) {
                _uiState.value = BillImportUiState(
                    ImportPhase.Failed("读取文件失败。请确认文件还在原位置，或从系统「文件管理」重新选择一次")
                )
                return@launch
            }
            pickedFile = file
            runImport(file, password = null)
        }
    }

    fun onPasswordSubmitted(password: String) {
        val file = pickedFile ?: return
        _uiState.value = BillImportUiState(ImportPhase.Running)
        viewModelScope.launch { runImport(file, password.ifBlank { null }) }
    }

    /** 用户放弃输入密码。 */
    fun onPasswordCancelled() {
        pickedFile = null
        _uiState.value = BillImportUiState(ImportPhase.Idle)
    }

    /** 结果页/错误页上的"再导一份"。 */
    fun reset() {
        pickedFile = null
        _uiState.value = BillImportUiState(ImportPhase.Idle)
    }

    private suspend fun runImport(file: PickedFile, password: String?) {
        val outcome = withContext(Dispatchers.IO) {
            // 解析几千行 + 解压，可能花几秒，且可能抛各种意料外的异常（畸形文件）。
            // 这里兜住，避免整个进程崩掉——用户只是选错了一个文件而已。
            runCatching { importer.import(file.name, file.bytes, password) }
                .getOrElse {
                    BillImportOutcome.Failure("导入出错：${it.message ?: it.javaClass.simpleName}")
                }
        }

        _uiState.value = BillImportUiState(
            when (outcome) {
                is BillImportOutcome.Success -> ImportPhase.Done(outcome.summary)
                BillImportOutcome.NeedsPassword ->
                    ImportPhase.AskPassword(fileName = file.name, wrongAttempt = false)
                BillImportOutcome.WrongPassword ->
                    ImportPhase.AskPassword(fileName = file.name, wrongAttempt = true)
                is BillImportOutcome.Failure -> ImportPhase.Failed(outcome.message)
            }
        )
    }

    private fun readPickedFile(uri: Uri): PickedFile? {
        return runCatching {
            val name = queryDisplayName(uri) ?: "账单文件"
            val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    // 账单文件不可能几十兆。读到异常大的东西说明选错了（比如选了个视频），
                    // 直接中止，别把内存吃满。
                    if (total > MAX_PICKED_BYTES) return@use null
                    out.write(buffer, 0, count)
                }
                out.toByteArray()
            } ?: return null
            if (bytes.isEmpty()) null else PickedFile(name = name, bytes = bytes)
        }.getOrNull()
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull()

    private data class PickedFile(val name: String, val bytes: ByteArray) {
        // ByteArray 用引用比较即可，这里重写只是为了让 data class 的 equals/hashCode 不报警告
        override fun equals(other: Any?): Boolean =
            this === other || (other is PickedFile && name == other.name && bytes.contentEquals(other.bytes))

        override fun hashCode(): Int = 31 * name.hashCode() + bytes.contentHashCode()
    }

    private companion object {
        /** 单文件上限 64 MB，与 BillArchiveReader 的上限保持一致。 */
        const val MAX_PICKED_BYTES = 64 * 1024 * 1024
    }
}
