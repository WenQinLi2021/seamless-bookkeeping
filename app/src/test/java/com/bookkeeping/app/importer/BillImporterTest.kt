package com.bookkeeping.app.importer

import com.bookkeeping.app.data.local.entity.TransactionEntity
import com.bookkeeping.app.data.repository.TransactionStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导入流程的决策测试。
 *
 * 这里不碰 Room、不碰 Android——[BillImporter] 依赖的两个东西（[TransactionStore] 和
 * [BillReader]）都是接口，用 10 行的假实现就能把「同一份账单导两次会不会重复记账」这种
 * 最容易出人命的问题钉死。
 */
class BillImporterTest {

    private fun importer(
        store: FakeStore,
        reader: BillReader = TextReader(BillSamples.SIMPLE_ALIPAY_CSV),
    ) = BillImporter(store = store, reader = reader)

    @Test
    fun `首次导入把账单里的收支写进库`() = runBlocking {
        val store = FakeStore()

        val outcome = importer(store).import("支付宝账单.csv", "csv".toByteArray())

        val summary = (outcome as BillImportOutcome.Success).summary
        assertEquals(2, summary.importedExpense)
        assertEquals(0, summary.importedIncome)
        assertEquals(2, store.rows.size)
        assertEquals(BillPlatform.ALIPAY, summary.platform)
    }

    @Test
    fun `同一份账单导第二次一笔都不会重复记`() = runBlocking {
        val store = FakeStore()
        val importer = importer(store)
        val bytes = "csv".toByteArray()

        importer.import("支付宝账单.csv", bytes)
        val second = importer.import("支付宝账单.csv", bytes)

        val summary = (second as BillImportOutcome.Success).summary
        assertEquals(0, summary.importedTotal)
        assertEquals(2, summary.duplicateAlreadyImported)
        // 库里仍然是 2 条，没有翻倍
        assertEquals(2, store.rows.size)
    }

    @Test
    fun `自动抓取已经记过的同金额消费不再重复插入`() = runBlocking {
        val store = FakeStore()
        // 模拟：通知通道已经记下了这笔 45.00 的消费（不带单号）
        store.sameAmountNear = TransactionEntity(
            amountCents = 4500L,
            type = "EXPENSE",
            category = "FOOD",
            merchant = "肯德基",
            source = "ALIPAY",
            timestamp = 0L,
        )

        val outcome = importer(store).import("支付宝账单.csv", "csv".toByteArray())

        val summary = (outcome as BillImportOutcome.Success).summary
        assertEquals(2, summary.duplicateLikelyCaptured)
        assertEquals(0, summary.importedTotal)
        assertEquals(0, store.rows.size)
    }

    @Test
    fun `去重用的时间窗口是正负五分钟`() = runBlocking {
        val store = FakeStore()

        importer(store).import("支付宝账单.csv", "csv".toByteArray())

        assertEquals(2, store.queriedWindows.size)
        assertTrue(store.queriedWindows.all { it == 5 * 60 * 1000L })
    }

    @Test
    fun `导入时带上平台来源和自动分类`() = runBlocking {
        val store = FakeStore()

        importer(store).import("支付宝账单.csv", "csv".toByteArray())

        val kfc = store.rows.single { it.merchant == "肯德基" }
        assertEquals("ALIPAY", kfc.source)
        assertEquals(4500L, kfc.amountCents)
        assertEquals("EXPENSE", kfc.type)
        // 「肯德基」命中餐饮关键词
        assertEquals("FOOD", kfc.category)
        assertEquals("alipay:2026091022001zzzzzzzzzzz", kfc.externalId)
    }

    @Test
    fun `空文件直接失败不报异常`() = runBlocking {
        val outcome = importer(FakeStore()).import("空的.csv", ByteArray(0))

        assertTrue(outcome is BillImportOutcome.Failure)
    }

    @Test
    fun `读不出账单表头时给出可读的失败提示`() = runBlocking {
        val reader = TextReader("今天天气不错\n去超市买了点菜")
        val outcome = importer(FakeStore(), reader).import("随手记.txt", "x".toByteArray())

        val failure = outcome as BillImportOutcome.Failure
        assertTrue(failure.message.contains("账单"))
    }

    @Test
    fun `压缩包要密码时向UI要一次密码而不是直接失败`() = runBlocking {
        val outcome = importer(FakeStore(), object : BillReader {
            override fun read(bytes: ByteArray, fileName: String?, password: String?) =
                ArchiveReadResult.NeedsPassword
        }).import("账单.zip", "x".toByteArray())

        assertTrue(outcome is BillImportOutcome.NeedsPassword)
    }

    @Test
    fun `密码填错时返回WrongPassword让用户原地重填`() = runBlocking {
        val outcome = importer(FakeStore(), object : BillReader {
            override fun read(bytes: ByteArray, fileName: String?, password: String?) =
                ArchiveReadResult.WrongPassword
        }).import("账单.zip", "x".toByteArray(), password = "123456")

        assertTrue(outcome is BillImportOutcome.WrongPassword)
    }

    @Test
    fun `明细全部被跳过时如实统计而不是假装什么都没发生`() = runBlocking {
        val onlySkipped = """
            支付宝交易记录明细查询
            ---------------------------------交易记录明细列表------------------------------------
            交易号,商家订单号,交易创建时间,付款时间,交易对方,商品名称,金额（元）,收/支,交易状态,备注
            2026091022001zzzzzzzzzzz,T200P0000000099,2026-09-10 20:00:00,2026-09-10 20:00:05,余额宝,余额宝-单次转入,"45.00",不计收支,交易成功,
        """.trimIndent()

        val outcome = importer(FakeStore(), TextReader(onlySkipped))
            .import("支付宝账单.csv", "csv".toByteArray())

        val summary = (outcome as BillImportOutcome.Success).summary
        assertEquals(0, summary.importedTotal)
        assertEquals(1, summary.skippedTotal)
    }

    // ===== 测试替身 =====

    private class TextReader(private val text: String) : BillReader {
        override fun read(bytes: ByteArray, fileName: String?, password: String?) =
            ArchiveReadResult.Text(text, fileName ?: "账单文件")
    }

    private class FakeStore : TransactionStore {
        val rows = mutableListOf<TransactionEntity>()
        val externalIds = mutableSetOf<String>()
        val queriedWindows = mutableListOf<Long>()

        /** 模拟"通知通道已经抓过一笔同金额消费"。 */
        var sameAmountNear: TransactionEntity? = null

        override suspend fun insertAll(transactions: List<TransactionEntity>): List<Long> =
            transactions.map { transaction ->
                val externalId = transaction.externalId
                if (externalId != null && !externalIds.add(externalId)) {
                    -1L
                } else {
                    rows.add(transaction)
                    rows.size.toLong()
                }
            }

        override suspend fun findByExternalId(externalId: String): TransactionEntity? =
            rows.firstOrNull { it.externalId == externalId }

        override suspend fun findSameAmountNear(
            source: String,
            amountCents: Long,
            centerMs: Long,
            windowMs: Long,
        ): TransactionEntity? {
            queriedWindows.add(windowMs)
            return sameAmountNear
        }
    }
}
