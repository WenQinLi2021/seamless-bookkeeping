package com.bookkeeping.app.importer

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Calendar
import java.util.TimeZone

/**
 * 支付宝 / 微信 账单 CSV 解析。
 *
 * 两份账单的共同坑：
 * 1. 文件前面有十几行导出说明，真正的表头在中间，不能假定「第一行就是表头」；
 * 2. 表头列名带空格、全角括号，甚至跨版本改名字，所以按「别名 + 归一化」匹配列，而不是写死列号；
 * 3. 文件末尾有分割线和汇总行，必须识别并停止，否则会把汇总当交易。
 */
object BillStatementParser {

    private val TZ: TimeZone = TimeZone.getTimeZone("Asia/Shanghai")

    private val TIME_PATTERN = Regex(
        """(\d{4})\s*[-/年.]\s*(\d{1,2})\s*[-/月.]\s*(\d{1,2})\s*日?\s*(\d{1,2})\s*[:时]\s*(\d{1,2})(?:\s*[:分]\s*(\d{1,2}))?"""
    )

    private enum class Field {
        TIME, PAY_TIME, CREATE_TIME, DIRECTION, AMOUNT,
        COUNTERPARTY, PRODUCT, STATUS, ORDER_ID, MERCHANT_ORDER_ID,
        PAY_METHOD, REMARK,
    }

    private val ALIASES: Map<Field, List<String>> = mapOf(
        Field.TIME to listOf("交易时间", "交易日期", "时间"),
        Field.PAY_TIME to listOf("付款时间"),
        Field.CREATE_TIME to listOf("交易创建时间", "创建时间"),
        // 收/支 在不同导出里会写成 收支
        Field.DIRECTION to listOf("收/支", "收支", "收支类型"),
        Field.AMOUNT to listOf("金额(元)", "金额", "交易金额", "金额(元)", "金额（元）"),
        Field.COUNTERPARTY to listOf("交易对方", "对方", "商户名称"),
        Field.PRODUCT to listOf("商品", "商品名称", "商品说明", "交易说明"),
        Field.STATUS to listOf("当前状态", "交易状态", "状态"),
        Field.ORDER_ID to listOf("交易单号", "交易号", "交易订单号"),
        Field.MERCHANT_ORDER_ID to listOf("商户单号", "商家订单号", "商户订单号"),
        Field.PAY_METHOD to listOf("支付方式", "收/付款方式", "付款方式"),
        Field.REMARK to listOf("备注"),
    )

    private val NOT_PAID_KEYWORDS = listOf("关闭", "失败", "未支付", "已失效", "已撤销", "已取消", "待付款", "未完成")
    private val REFUND_KEYWORDS = listOf("退款", "退还")

    /**
     * 解析一个账单文件。
     *
     * @return 识别不出平台/表头时返回 null，由调用方转成用户可读的失败提示。
     */
    fun parse(text: String): ParsedBill? {
        val rows = CsvTable.parse(text).map { CsvTable.normalizeCells(it) }
        val headerIndex = rows.indexOfFirst { detectPlatform(it) != null }
        if (headerIndex < 0) return null

        val header = rows[headerIndex]
        val platform = requireNotNull(detectPlatform(header))
        val columns = mapColumns(header)

        val records = ArrayList<BillRecord>()
        val skipped = ArrayList<SkippedBillRow>()

        for (i in headerIndex + 1 until rows.size) {
            val row = rows[i]
            if (isBoundaryRow(row)) break
            when (val result = parseRow(platform, columns, row)) {
                is RowResult.Record -> records.add(result.record)
                is RowResult.Skip -> skipped.add(SkippedBillRow(result.reason, row.joinToString(" | ")))
                RowResult.Ignore -> Unit
            }
        }
        return ParsedBill(platform, records, skipped)
    }

    /** 通过表头特征判断平台；不是账单表头时返回 null。 */
    private fun detectPlatform(row: List<String>): BillPlatform? {
        if (row.size < 5) return null
        val cells = row.map { normalize(it) }
        val hasDirection = cells.any { it == "收/支" || it == "收支" }
        val hasAmount = cells.any { it.startsWith("金额") }
        if (!hasDirection || !hasAmount) return null

        val wechatId = cells.any { it == "交易单号" || it == "商户单号" }
        val alipayId = cells.any { it == "交易号" || it == "商家订单号" }
        // 支付宝也有「交易对方 / 收/支」，靠 ID 列名区分最稳
        return when {
            wechatId -> BillPlatform.WECHAT
            alipayId -> BillPlatform.ALIPAY
            // 兜底：微信账单一定有「当前状态」，支付宝是「交易状态」
            cells.any { it == "当前状态" } -> BillPlatform.WECHAT
            cells.any { it == "交易状态" } -> BillPlatform.ALIPAY
            else -> null
        }
    }

    private fun mapColumns(header: List<String>): Map<Field, Int> {
        val normalized = header.map { normalize(it) }
        val result = LinkedHashMap<Field, Int>()
        // 先精确匹配，避免「商品」抢走「商品名称」这类误配
        for ((field, aliases) in ALIASES) {
            val target = aliases.map { normalize(it) }.toSet()
            val index = normalized.indexOfFirst { it.isNotEmpty() && it in target }
            if (index >= 0) result[field] = index
        }
        // 再对没匹配上的字段做一次包含匹配（容忍「金额(元)（含退款）」这类后缀）
        for ((field, aliases) in ALIASES) {
            if (result.containsKey(field)) continue
            val index = normalized.indexOfFirst { cell ->
                cell.isNotEmpty() && aliases.any { normalize(it).let { a -> a.isNotEmpty() && cell.contains(a) } }
            }
            if (index >= 0) result[field] = index
        }
        return result
    }

    private sealed interface RowResult {
        data class Record(val record: BillRecord) : RowResult
        data class Skip(val reason: SkipReason) : RowResult
        data object Ignore : RowResult
    }

    private fun parseRow(
        platform: BillPlatform,
        columns: Map<Field, Int>,
        row: List<String>,
    ): RowResult {
        fun cell(field: Field): String = columns[field]?.let { row.getOrNull(it) }.orEmpty().trim()

        val timeCell = when (platform) {
            // 支付宝有独立的「付款时间」，比「交易创建时间」更接近真实扣款时间
            BillPlatform.ALIPAY -> listOf(Field.PAY_TIME, Field.CREATE_TIME, Field.TIME)
                .map { cell(it) }.firstOrNull { it.isNotBlank() }.orEmpty()
            BillPlatform.WECHAT -> listOf(Field.TIME, Field.CREATE_TIME)
                .map { cell(it) }.firstOrNull { it.isNotBlank() }.orEmpty()
        }
        val amountCell = cell(Field.AMOUNT)
        // 既没有时间也没有金额 → 大概率是说明行，直接忽略（不计入「解析失败」）
        if (timeCell.isBlank() && amountCell.isBlank()) return RowResult.Ignore

        val status = cell(Field.STATUS)
        when {
            NOT_PAID_KEYWORDS.any { status.contains(it) } -> return RowResult.Skip(SkipReason.NOT_PAID)
            REFUND_KEYWORDS.any { status.contains(it) } -> return RowResult.Skip(SkipReason.REFUNDED)
        }

        val direction = when (cell(Field.DIRECTION).replace(" ", "")) {
            "收入", "收", "转入" -> BillDirection.INCOME
            "支出", "支", "转出" -> BillDirection.EXPENSE
            // 不计收支 / / / 空：提现、还款、账户间转账、余额宝转入等
            else -> return RowResult.Skip(SkipReason.NEUTRAL)
        }

        val amountCents = parseAmountCents(amountCell) ?: return RowResult.Skip(SkipReason.INVALID)
        val timestampMs = parseTimeMs(timeCell) ?: return RowResult.Skip(SkipReason.INVALID)

        val merchant = cell(Field.COUNTERPARTY).takeUnless { it.isBlank() || it == "/" || it == "-" }.orEmpty()
        val note = listOf(cell(Field.PRODUCT), cell(Field.REMARK))
            .filter { it.isNotBlank() && it != "/" }
            .distinct()
            .joinToString(" ")
        val account = cell(Field.PAY_METHOD).takeUnless { it.isBlank() || it == "/" }

        // 微信优先用「交易单号」；支付宝优先用「交易号」。商户单号作兜底。
        val primaryId = cell(Field.ORDER_ID)
        val fallbackId = cell(Field.MERCHANT_ORDER_ID)
        val rawId = listOf(primaryId, fallbackId)
            .map { it.replace(" ", "").replace("\u3000", "") }
            .firstOrNull { it.isNotBlank() && it != "/" && it != "-" && it.length >= 6 }

        return RowResult.Record(
            BillRecord(
                platform = platform,
                externalId = rawId?.let { "${platform.name.lowercase()}:$it" },
                timestampMs = timestampMs,
                amountCents = amountCents,
                direction = direction,
                merchant = merchant,
                note = note,
                account = account,
                status = status,
            )
        )
    }

    /** 账单末尾的分割线 / 汇总行 / 重复表头，出现即停止解析。 */
    private fun isBoundaryRow(row: List<String>): Boolean {
        val first = row.firstOrNull().orEmpty().trim()
        if (first.startsWith("-")) return true
        if (row.count { it.isNotBlank() } < 3) return true
        if (first.contains("以上") || first.contains("明细列表")) return true
        if (detectPlatform(row) != null) return true
        return false
    }

    /** 金额单元格可能长这样：`¥12.50`、`="¥12.50"`、`1,234.56`、`12.50 元`。 */
    internal fun parseAmountCents(raw: String): Long? {
        val cleaned = raw
            .replace("\"", "")
            .replace("=", "")
            .replace("¥", "")
            .replace("￥", "")
            .replace("元", "")
            .replace(",", "")
            .replace(" ", "")
            .replace("\u3000", "")
            .trim()
        if (cleaned.isEmpty()) return null
        return runCatching {
            BigDecimal(cleaned)
                .movePointRight(2)
                .setScale(0, RoundingMode.HALF_UP)
                .toLong()
        }.getOrNull()?.takeIf { it > 0 }
    }

    /** 账单里的时间形如 `2026-09-05 12:30:45`、`2026/09/05 12:30`、`2026年9月5日 12:30`。 */
    internal fun parseTimeMs(raw: String): Long? {
        val match = TIME_PATTERN.find(raw) ?: return null
        val year = match.groupValues[1].toIntOrNull() ?: return null
        val month = match.groupValues[2].toIntOrNull() ?: return null
        val day = match.groupValues[3].toIntOrNull() ?: return null
        val hour = match.groupValues[4].toIntOrNull() ?: return null
        val minute = match.groupValues[5].toIntOrNull() ?: return null
        val second = match.groupValues[6].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 0
        if (year !in 2000..2100) return null
        if (month !in 1..12 || day !in 1..31) return null
        if (hour !in 0..23 || minute !in 0..59 || second !in 0..59) return null

        return Calendar.getInstance(TZ).apply {
            clear()
            set(year, month - 1, day, hour, minute, second)
        }.timeInMillis
    }

    /** 表头归一化：去全角括号、去所有空白、去掉 BOM，英文转小写。 */
    internal fun normalize(cell: String): String = cell
        .replace("\uFEFF", "")
        .replace('\u3000', ' ')
        .replace('（', '(')
        .replace('）', ')')
        .replace(Regex("\\s+"), "")
        .trim()
        .lowercase()
}
