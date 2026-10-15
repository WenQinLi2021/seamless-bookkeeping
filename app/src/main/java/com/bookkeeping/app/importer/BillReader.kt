package com.bookkeeping.app.importer

/**
 * 「把用户选的文件变成文本」这一步的抽象。
 *
 * 真实实现是 [BillArchiveReader]（要解压、要 Context 写临时文件）。把接口抽出来是为了让
 * [BillImporter] 的去重/入库逻辑能在纯 JVM 单测里跑——那条逻辑才是最容易出错的部分
 * （判重条件写错就会静默重复记账），值得用测试钉死。
 */
interface BillReader {
    fun read(bytes: ByteArray, fileName: String?, password: String?): ArchiveReadResult
}
