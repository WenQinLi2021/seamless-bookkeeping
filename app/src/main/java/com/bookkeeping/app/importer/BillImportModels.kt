package com.bookkeeping.app.importer

/**
 * 账单文件来源平台。
 *
 * 支付宝与微信导出的 CSV 列名、编码、表头行数都不同，先识别平台再解析。
 */
enum class BillPlatform(val displayName: String) {
    ALIPAY("支付宝"),
    WECHAT("微信"),
}

/** 账单行里的收/支方向。不与 Room 实体耦合，方便纯 JVM 单测。 */
enum class BillDirection { EXPENSE, INCOME }

/**
 * 一行账单数据解析成功后的结果。
 *
 * 金额一律用「分」存储，避免浮点误差（与 TransactionEntity 保持一致）。
 */
data class BillRecord(
    val platform: BillPlatform,
    /** 平台内部唯一标识（微信交易单号 / 支付宝交易号）。用于导入去重，可能为空。 */
    val externalId: String?,
    val timestampMs: Long,
    val amountCents: Long,
    val direction: BillDirection,
    val merchant: String,
    val note: String,
    val account: String?,
    val status: String,
)

/** 被跳过的行及原因。统计出来展示给用户，避免"悄悄少记一笔"。 */
enum class SkipReason(val displayName: String) {
    /** 收/支 = 不计收支，例如提现、还款、账户内部转账 */
    NEUTRAL("不计收支（转账/还款等）"),
    /** 交易未真正完成，例如已关闭、支付失败 */
    NOT_PAID("交易未完成（已关闭/失败）"),
    /** 退款、已退还 */
    REFUNDED("退款/已退还"),
    /** 时间、金额等关键字段解析失败 */
    INVALID("字段无法解析"),
}

data class SkippedBillRow(val reason: SkipReason, val rawLine: String)

/** 一个账单文件的解析结果。 */
data class ParsedBill(
    val platform: BillPlatform,
    val records: List<BillRecord>,
    val skipped: List<SkippedBillRow>,
) {
    val dataRowCount: Int get() = records.size + skipped.size
}

/** 导入后的统计，直接展示给用户。 */
data class BillImportSummary(
    val platform: BillPlatform,
    val fileName: String,
    val importedExpense: Int,
    val importedIncome: Int,
    val duplicateAlreadyImported: Int,
    val duplicateLikelyCaptured: Int,
    val skipped: Map<SkipReason, Int>,
    val earliestMs: Long?,
    val latestMs: Long?,
) {
    val importedTotal: Int get() = importedExpense + importedIncome
    val skippedTotal: Int get() = skipped.values.sum()
}

/** 导入流程的最终结果。 */
sealed interface BillImportOutcome {
    data class Success(val summary: BillImportSummary) : BillImportOutcome

    /** 压缩包带密码且尚未提供，需要 UI 追问一次。 */
    data object NeedsPassword : BillImportOutcome

    /** 提供的密码不对，UI 应该让用户原地重填（不必重新选文件）。 */
    data object WrongPassword : BillImportOutcome

    data class Failure(val message: String) : BillImportOutcome
}
