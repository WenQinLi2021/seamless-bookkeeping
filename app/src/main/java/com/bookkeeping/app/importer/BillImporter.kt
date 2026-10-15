package com.bookkeeping.app.importer

import com.bookkeeping.app.data.local.entity.TransactionEntity
import com.bookkeeping.app.data.local.entity.TransactionSource
import com.bookkeeping.app.data.local.entity.TransactionType
import com.bookkeeping.app.data.repository.TransactionStore
import com.bookkeeping.app.notification.CategoryClassifier
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 账单导入主流程：解压 → 解码 → 解析 → 去重 → 入库 → 汇总。
 *
 * 去重分两层，缺一不可：
 * 1. **外部单号**：账单里的交易单号/交易号。同一份账单导入两次只会命中一次。
 * 2. **金额 + 时间窗口**：通知通道抓到的记录没有单号，但它和账单里的同金额、同来源、
 *    时间相近的记录是同一笔。窗口取 ±5 分钟——足够容下通知延迟，又不会把同一天
 *    两笔金额相同的正常消费合并掉。
 */
@Singleton
class BillImporter @Inject constructor(
    private val store: TransactionStore,
    private val reader: BillReader,
) {

    suspend fun import(
        fileName: String?,
        bytes: ByteArray,
        password: String? = null,
    ): BillImportOutcome {
        if (bytes.isEmpty()) return BillImportOutcome.Failure("文件内容为空")

        val decoded = when (val result = reader.read(bytes, fileName, password)) {
            is ArchiveReadResult.Text -> result
            ArchiveReadResult.NeedsPassword -> return BillImportOutcome.NeedsPassword
            ArchiveReadResult.WrongPassword -> return BillImportOutcome.WrongPassword
            is ArchiveReadResult.Failure -> return BillImportOutcome.Failure(result.message)
        }

        val bill = BillStatementParser.parse(decoded.text)
            ?: return BillImportOutcome.Failure(
                "没有在文件里找到支付宝或微信的账单表头。\n" +
                    "请确认选择的是「用于个人对账」导出的 CSV 文件（压缩包请选择解压后的 csv，或直接选 zip）。"
            )
        if (bill.dataRowCount == 0) {
            return BillImportOutcome.Failure("文件里没有可解析的账单明细行，可能选错了文件")
        }

        var duplicateAlreadyImported = 0
        var duplicateLikelyCaptured = 0
        val pending = ArrayList<TransactionEntity>()
        val seenExternalIds = HashSet<String>()

        for (record in bill.records) {
            val externalId = record.externalId
            if (externalId != null) {
                // 同一份账单内部也可能出现重复行，先在内存里挡一次
                if (!seenExternalIds.add(externalId)) {
                    duplicateAlreadyImported++
                    continue
                }
                if (store.findByExternalId(externalId) != null) {
                    duplicateAlreadyImported++
                    continue
                }
            }
            val source = sourceOf(record.platform)
            val captured = store.findSameAmountNear(
                source = source,
                amountCents = record.amountCents,
                centerMs = record.timestampMs,
                windowMs = CAPTURE_DEDUP_WINDOW_MS,
            )
            if (captured != null) {
                duplicateLikelyCaptured++
                continue
            }
            pending.add(record.toEntity())
        }

        // 一次性批量插入，避免几千行逐条开事务。
        // 用返回的 rowId 判断到底进去了几行：列表里是唯一的，但极少数情况下
        // （并发导入同一份文件）唯一索引仍可能挡下一部分，-1 就是被挡下的。
        val inserted = if (pending.isEmpty()) {
            emptyList()
        } else {
            store.insertAll(pending).mapIndexedNotNull { index, rowId ->
                if (rowId == -1L) null else pending[index]
            }
        }

        val skippedCounts = bill.skipped.groupingBy { it.reason }.eachCount()

        return BillImportOutcome.Success(
            BillImportSummary(
                platform = bill.platform,
                fileName = decoded.sourceName,
                importedExpense = inserted.count { it.type == TransactionType.EXPENSE.name },
                importedIncome = inserted.count { it.type == TransactionType.INCOME.name },
                duplicateAlreadyImported = duplicateAlreadyImported,
                duplicateLikelyCaptured = duplicateLikelyCaptured,
                skipped = skippedCounts,
                earliestMs = inserted.minOfOrNull { it.timestamp },
                latestMs = inserted.maxOfOrNull { it.timestamp },
            )
        )
    }

    private fun sourceOf(platform: BillPlatform): String = when (platform) {
        BillPlatform.ALIPAY -> TransactionSource.ALIPAY.name
        BillPlatform.WECHAT -> TransactionSource.WECHAT.name
    }

    private fun BillRecord.toEntity(): TransactionEntity = TransactionEntity(
        amountCents = amountCents,
        type = when (direction) {
            BillDirection.EXPENSE -> TransactionType.EXPENSE.name
            BillDirection.INCOME -> TransactionType.INCOME.name
        },
        category = CategoryClassifier.classify(merchant, note).name,
        merchant = merchant,
        note = note,
        source = sourceOf(platform),
        account = account,
        timestamp = timestampMs,
        externalId = externalId,
    )

    private companion object {
        const val CAPTURE_DEDUP_WINDOW_MS = 5 * 60 * 1000L
    }
}
