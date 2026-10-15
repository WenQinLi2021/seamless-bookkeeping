package com.bookkeeping.app.ui.screen.importer

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bookkeeping.app.importer.BillImportSummary
import com.bookkeeping.app.importer.BillPlatform
import com.bookkeeping.app.importer.SkipReason
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// 账单文件没有可靠的 MIME 类型：不同 ROM 的文件管理器给 csv 报 text/csv、text/plain、
// application/octet-stream 甚至 application/vnd.ms-excel 都有，zip 也一样。
// 与其列一堆类型让用户"看不到自己的文件"，不如放开筛选，靠下面的文案引导 + 导入器校验兜底。
private val BILL_MIME_TYPES = arrayOf("*/*")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BillImportScreen(viewModel: BillImportViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri -> viewModel.onFilePicked(uri) }

    Scaffold(topBar = { TopAppBar(title = { Text("账单导入") }) }) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (val phase = state.phase) {
                ImportPhase.Idle -> {
                    item { IntroCard() }
                    item { HowToExportCard() }
                    item {
                        Button(
                            onClick = { filePicker.launch(BILL_MIME_TYPES) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Filled.UploadFile, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("选择账单文件（csv / zip）")
                        }
                    }
                    item { DedupCard() }
                }

                ImportPhase.Running -> item { RunningCard() }

                is ImportPhase.Done -> {
                    item { SummaryCard(phase.summary) }
                    item { AgainButton(viewModel::reset) }
                }

                is ImportPhase.Failed -> {
                    item { ErrorCard(phase.message) }
                    item {
                        Button(
                            onClick = { filePicker.launch(BILL_MIME_TYPES) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("重新选择文件")
                        }
                    }
                }

                is ImportPhase.AskPassword -> {
                    item { PasswordHintCard(phase.fileName) }
                    item {
                        FilledTonalButton(
                            onClick = viewModel::onPasswordCancelled,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("换一个文件")
                        }
                    }
                }
            }
        }
    }

    val passwordPhase = state.phase as? ImportPhase.AskPassword
    if (passwordPhase != null) {
        PasswordDialog(
            wrongAttempt = passwordPhase.wrongAttempt,
            onSubmit = viewModel::onPasswordSubmitted,
            onDismiss = viewModel::onPasswordCancelled,
        )
    }
}

@Composable
private fun IntroCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "把历史账单一次性补进来",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                text = "自动记账只能从装上那天开始记。之前的消费记录，用支付宝/微信官方导出的账单" +
                       "一次性导入，账本才是完整的。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(top = 4.dp),
            )
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 10.dp),
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f),
            )
            Text(
                text = "支持的文件\n" +
                       "· 支付宝：交易流水证明（用于个人对账）导出的 .csv 或 .zip\n" +
                       "· 微信：账单下载（用于个人对账）导出的 .csv 或 .zip\n" +
                       "· 压缩包带密码也能导，会问你一次密码\n" +
                       "· 编码 UTF-8 / GBK 都能识别，手机上收到的一般就是 GBK",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun HowToExportCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "怎么拿到账单文件",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))

            StepBlock(
                title = "支付宝",
                steps = listOf(
                    "我的 → 账单 → 右上角「···」→ 开具交易流水证明",
                    "用途选「用于个人对账」，选好时间范围",
                    "填邮箱，确认后去邮箱下载 zip",
                ),
                passwordHint = "解压密码见申请页面和邮件正文的说明（通常是身份证号后 6 位）",
            )
            Spacer(Modifier.height(12.dp))
            StepBlock(
                title = "微信",
                steps = listOf(
                    "我 → 服务 → 钱包 → 账单 → 右上角「常见问题」",
                    "下载账单 → 用于个人对账 → 选时间范围",
                    "填邮箱，确认后去邮箱下载 zip",
                ),
                passwordHint = "「微信支付」会推送一条服务通知，解压密码写在里面",
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "导出的文件一般会直接下到手机的「下载」目录。如果文件管理器里找不到，" +
                       "把 zip 先解压成 csv 再选也行。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StepBlock(title: String, steps: List<String>, passwordHint: String) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
        steps.forEachIndexed { index, step ->
            Text(
                text = "${index + 1}. $step",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Text(
            text = "密码：$passwordHint",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun DedupCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "重复导入是安全的",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "· 同一份账单重复导，第二次会全部按单号认出来，一笔都不会重复记。\n" +
                       "· 已经自动抓到的消费（比如刚付完款的通知），导入时按「同来源 + 同金额 + " +
                       "时间相差 5 分钟内」识别为同一笔，不会记两次。\n" +
                       "· 提现、还款、账户间转账、退款这些不计入收支的行会被跳过，并在结果里告诉你跳了几笔。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun RunningCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator()
            Column(modifier = Modifier.padding(start = 16.dp)) {
                Text(
                    text = "正在解析账单…",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "几千行的账单要解压 + 逐行解析 + 去重，稍等几秒。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SummaryCard(summary: BillImportSummary) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                Text(
                    text = "导入完成",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Text(
                text = "${summary.platform.displayName} · ${summary.fileName}",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 10.dp),
                color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.2f),
            )

            StatRow("新增支出", "${summary.importedExpense} 笔")
            StatRow("新增收入", "${summary.importedIncome} 笔")
            if (summary.duplicateAlreadyImported > 0) {
                StatRow("已导入过，跳过", "${summary.duplicateAlreadyImported} 笔")
            }
            if (summary.duplicateLikelyCaptured > 0) {
                StatRow("自动记账已抓到，跳过", "${summary.duplicateLikelyCaptured} 笔")
            }
            SkipReason.entries.forEach { reason ->
                val count = summary.skipped[reason] ?: 0
                if (count > 0) StatRow(reason.displayName, "$count 笔")
            }

            val earliest = summary.earliestMs
            val latest = summary.latestMs
            if (earliest != null && latest != null) {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 10.dp),
                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.2f),
                )
                StatRow("覆盖时间段", "${formatDate(earliest)} ~ ${formatDate(latest)}")
            }

            Text(
                text = "去「账单」页就能看到这些记录了。",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun ErrorCard(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    text = "没能导入",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun PasswordHintCard(fileName: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "这个账单包有密码",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "$fileName\n\n" +
                       "微信给的密码在「微信支付」的服务通知里，支付宝的一般是身份证号后 6 位。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun AgainButton(onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text("再导入一份")
    }
}

@Composable
private fun PasswordDialog(
    wrongAttempt: Boolean,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var password by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (wrongAttempt) "密码不对，再试一次" else "输入账单包密码") },
        text = {
            Column {
                if (wrongAttempt) {
                    Text(
                        text = "上一次填的密码解不开这个压缩包。注意大小写，身份证里的 X 要大写。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("解压密码") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                Text(
                    text = "密码只在这一步用一次，不会被保存到手机上。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSubmit(password) },
                enabled = password.isNotBlank(),
            ) { Text("解压并导入") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

private fun formatDate(ms: Long): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(ms))
