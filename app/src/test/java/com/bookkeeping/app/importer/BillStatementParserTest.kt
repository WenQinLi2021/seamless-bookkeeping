package com.bookkeeping.app.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class BillStatementParserTest {

    // ===== 支付宝 =====

    @Test
    fun `识别支付宝账单并只把真正发生的收支计入`() {
        val bill = parseOrFail(BillSamples.ALIPAY_CSV)

        assertEquals(BillPlatform.ALIPAY, bill.platform)
        // 6 行明细里：3 笔真实收支（2 支出 1 收入），另外 3 笔分别是不计收支/已关闭/退款
        assertEquals(3, bill.records.size)
        assertEquals(2, bill.records.count { it.direction == BillDirection.EXPENSE })
        assertEquals(1, bill.records.count { it.direction == BillDirection.INCOME })
        assertEquals(6, bill.dataRowCount)
    }

    @Test
    fun `余额宝转入这类不计收支的行被跳过而不是记成支出`() {
        val bill = parseOrFail(BillSamples.ALIPAY_CSV)

        assertEquals(1, bill.skipped.count { it.reason == SkipReason.NEUTRAL })
        assertTrue(bill.records.none { it.merchant == "余额宝" })
    }

    @Test
    fun `已关闭的交易被跳过`() {
        val bill = parseOrFail(BillSamples.ALIPAY_CSV)

        assertEquals(1, bill.skipped.count { it.reason == SkipReason.NOT_PAID })
        assertTrue(bill.records.none { it.note.contains("已关闭") })
    }

    @Test
    fun `退款交易被跳过`() {
        val bill = parseOrFail(BillSamples.ALIPAY_CSV)

        assertEquals(1, bill.skipped.count { it.reason == SkipReason.REFUNDED })
        assertTrue(bill.records.none { it.amountCents == 3000L })
    }

    @Test
    fun `支付宝优先采用付款时间而不是交易创建时间`() {
        val bill = parseOrFail(BillSamples.ALIPAY_CSV)

        val starbucks = bill.records.single { it.merchant == "星巴克咖啡" }
        // 付款时间 12:30:50，而交易创建时间是 12:30:45
        assertEquals(shanghaiMs(2026, 9, 1, 12, 30, 50), starbucks.timestampMs)
    }

    @Test
    fun `金额按分存储且单号带上平台前缀`() {
        val bill = parseOrFail(BillSamples.ALIPAY_CSV)

        val starbucks = bill.records.single { it.merchant == "星巴克咖啡" }
        assertEquals(1250L, starbucks.amountCents)
        assertEquals("alipay:2026090122001aaaaaaaaaaa", starbucks.externalId)
    }

    @Test
    fun `支付宝的收入行被正确识别`() {
        val bill = parseOrFail(BillSamples.ALIPAY_CSV)

        val salary = bill.records.single { it.direction == BillDirection.INCOME }
        assertEquals(800000L, salary.amountCents)
        assertEquals("某某科技有限公司", salary.merchant)
    }

    // ===== 微信 =====

    @Test
    fun `识别微信账单并解析出收支`() {
        val bill = parseOrFail(BillSamples.WECHAT_CSV)

        assertEquals(BillPlatform.WECHAT, bill.platform)
        assertEquals(3, bill.records.size)
        assertEquals(2, bill.records.count { it.direction == BillDirection.EXPENSE })
        assertEquals(1, bill.records.count { it.direction == BillDirection.INCOME })
    }

    @Test
    fun `微信账单里已全额退款的行被跳过`() {
        val bill = parseOrFail(BillSamples.WECHAT_CSV)

        assertEquals(1, bill.skipped.count { it.reason == SkipReason.REFUNDED })
        assertTrue(bill.records.none { it.amountCents == 5000L })
    }

    @Test
    fun `微信的金额前缀人民币符号被去掉`() {
        val bill = parseOrFail(BillSamples.WECHAT_CSV)

        val starbucks = bill.records.single { it.merchant == "星巴克" }
        assertEquals(1250L, starbucks.amountCents)
    }

    @Test
    fun `商品名里的逗号不会把这一行拆错列`() {
        val bill = parseOrFail(BillSamples.WECHAT_CSV)

        val starbucks = bill.records.single { it.merchant == "星巴克" }
        // 原始单元格是 "星巴克消费,中杯"，如果按逗号硬 split 会串列
        assertEquals("星巴克消费,中杯", starbucks.note)
        // 串列的话支付方式会跑到别的列去
        assertEquals("零钱", starbucks.account)
    }

    @Test
    fun `微信单号带上wechat前缀`() {
        val bill = parseOrFail(BillSamples.WECHAT_CSV)

        val starbucks = bill.records.single { it.merchant == "星巴克" }
        assertEquals("wechat:4200001234202609011234567890", starbucks.externalId)
    }

    @Test
    fun `备注为斜杠时不写进备注`() {
        val bill = parseOrFail(BillSamples.WECHAT_CSV)

        val redPacket = bill.records.single { it.direction == BillDirection.INCOME }
        assertEquals("好友甲", redPacket.merchant)
        assertEquals("", redPacket.note)
    }

    // ===== 边界 =====

    @Test
    fun `末尾分割线与汇总行不会被当成交易`() {
        val bill = parseOrFail(BillSamples.ALIPAY_CSV)

        // "共6笔记录" 如果被当成数据行，会出现一个金额解析失败的记录
        assertEquals(0, bill.skipped.count { it.reason == SkipReason.INVALID })
        assertTrue(bill.records.none { it.note.contains("共") })
    }

    @Test
    fun `完全无关的文本返回null`() {
        assertNull(BillStatementParser.parse("这是一份购物清单\n苹果,3斤\n香蕉,2斤"))
        assertNull(BillStatementParser.parse(""))
    }

    @Test
    fun `没有表头的空文件返回null而不是空账单`() {
        assertNull(BillStatementParser.parse("\n\n\n"))
    }

    // ===== 金额 / 时间的容错 =====

    @Test
    fun `金额支持带货币符号千分位与元字后缀`() {
        assertEquals(1250L, BillStatementParser.parseAmountCents("¥12.50"))
        assertEquals(1250L, BillStatementParser.parseAmountCents("￥12.50"))
        assertEquals(1250L, BillStatementParser.parseAmountCents("12.50 元"))
        assertEquals(123456L, BillStatementParser.parseAmountCents("1,234.56"))
        assertEquals(1200L, BillStatementParser.parseAmountCents("=\"12.00\""))
        assertEquals(100L, BillStatementParser.parseAmountCents("1"))
    }

    @Test
    fun `金额解析失败或为零时返回null`() {
        assertNull(BillStatementParser.parseAmountCents(""))
        assertNull(BillStatementParser.parseAmountCents("--"))
        assertNull(BillStatementParser.parseAmountCents("0.00"))
        assertNull(BillStatementParser.parseAmountCents("/"))
    }

    @Test
    fun `时间支持横杠斜杠年月日三种写法`() {
        val expected = shanghaiMs(2026, 9, 5, 12, 30, 45)
        assertEquals(expected, BillStatementParser.parseTimeMs("2026-09-05 12:30:45"))
        assertEquals(expected, BillStatementParser.parseTimeMs("2026/9/5 12:30:45"))
        assertEquals(expected, BillStatementParser.parseTimeMs("2026年9月5日 12:30:45"))
        assertEquals(expected, BillStatementParser.parseTimeMs("2026.09.05 12:30:45"))
    }

    @Test
    fun `时间没有秒时按0秒处理`() {
        assertEquals(
            shanghaiMs(2026, 9, 5, 12, 30, 0),
            BillStatementParser.parseTimeMs("2026-09-05 12:30"),
        )
    }

    @Test
    fun `非法时间返回null`() {
        assertNull(BillStatementParser.parseTimeMs(""))
        assertNull(BillStatementParser.parseTimeMs("昨天下午"))
        assertNull(BillStatementParser.parseTimeMs("2026-13-05 12:30:45"))
    }

    @Test
    fun `表头归一化忽略全角括号与空格`() {
        assertEquals("金额(元)", BillStatementParser.normalize(" 金额（元） "))
        assertEquals("收/支", BillStatementParser.normalize("收 / 支"))
    }

    private fun parseOrFail(text: String): ParsedBill {
        val parsed = BillStatementParser.parse(text)
        assertNotNull("账单应该能被识别，实际返回了 null", parsed)
        return requireNotNull(parsed)
    }

    private fun shanghaiMs(
        year: Int, month: Int, day: Int,
        hour: Int, minute: Int, second: Int = 0,
    ): Long = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai")).apply {
        clear()
        set(year, month - 1, day, hour, minute, second)
    }.timeInMillis
}
