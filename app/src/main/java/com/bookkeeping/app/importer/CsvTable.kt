package com.bookkeeping.app.importer

/**
 * 极简 CSV 解析器（按 RFC 4180 处理引号）。
 *
 * 为什么不直接用 String.split(",")：账单里的「商品」「备注」经常包含逗号，
 * 这些字段在导出时会被双引号包起来。用 split 会把一行拆散，导致列错位。
 */
object CsvTable {

    fun parse(text: String): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        var field = StringBuilder()
        var inQuotes = false
        var index = 0

        fun endField() {
            row.add(field.toString())
            field = StringBuilder()
        }

        fun endRow() {
            endField()
            // 完全空行直接丢弃，账单里用来分隔表头/数据的空行很多
            if (row.any { it.isNotBlank() } || row.size > 1) rows.add(row)
            row = ArrayList()
        }

        while (index < text.length) {
            val c = text[index]
            when {
                inQuotes -> when {
                    // 转义的双引号："" -> "
                    c == '"' && index + 1 < text.length && text[index + 1] == '"' -> {
                        field.append('"')
                        index++
                    }
                    c == '"' -> inQuotes = false
                    else -> field.append(c)
                }

                c == '"' -> inQuotes = true
                c == ',' -> endField()
                c == '\n' -> endRow()
                c == '\r' -> {
                    // 统一处理 CRLF，避免多出一个空行
                    if (index + 1 < text.length && text[index + 1] == '\n') index++
                    endRow()
                }
                else -> field.append(c)
            }
            index++
        }
        // 最后一行可能没有换行符
        if (field.isNotEmpty() || row.isNotEmpty()) endRow()

        return rows
    }

    /** 去掉每个单元格首尾空白，并把连续空白压成一个空格（账单表头常带奇怪空格）。 */
    fun normalizeCells(row: List<String>): List<String> =
        row.map { cell -> cell.trim().replace(Regex("\\s+"), " ") }
}
