package com.bookkeeping.app.importer

import org.junit.Assert.assertEquals
import org.junit.Test

class CsvTableTest {

    @Test
    fun `字段内的逗号被引号包住时不会被拆开`() {
        val rows = CsvTable.parse("""a,"b,c",d""")

        assertEquals(1, rows.size)
        assertEquals(listOf("a", "b,c", "d"), rows[0])
    }

    @Test
    fun `双引号转义按RFC4180还原成一个引号`() {
        // 原始文件里长这样：  商户,"他说""你好""",支出
        val rows = CsvTable.parse("商户,\"他说\"\"你好\"\"\",支出")

        assertEquals(listOf("商户", "他说\"你好\"", "支出"), rows[0])
    }

    @Test
    fun `CRLF换行只产生一行不会多出空行`() {
        val rows = CsvTable.parse("a,b\r\nc,d\r\n")

        assertEquals(2, rows.size)
        assertEquals(listOf("a", "b"), rows[0])
        assertEquals(listOf("c", "d"), rows[1])
    }

    @Test
    fun `最后一行没有换行符也要收进来`() {
        val rows = CsvTable.parse("a,b\nc,d")

        assertEquals(2, rows.size)
        assertEquals(listOf("c", "d"), rows[1])
    }

    @Test
    fun `完全空行被丢弃`() {
        val rows = CsvTable.parse("a,b\n\n\nc,d")

        assertEquals(2, rows.size)
    }

    @Test
    fun `normalizeCells把连续空白压成一个空格并去首尾空白`() {
        val normalized = CsvTable.normalizeCells(listOf("  金额（元）  ", "星巴克   咖啡", "\t支出\t"))

        assertEquals(listOf("金额（元）", "星巴克 咖啡", "支出"), normalized)
    }
}
