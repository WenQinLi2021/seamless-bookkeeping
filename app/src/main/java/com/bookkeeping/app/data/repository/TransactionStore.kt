package com.bookkeeping.app.data.repository

import com.bookkeeping.app.data.local.entity.TransactionEntity

/**
 * 账单导入器真正需要的最小数据能力。
 *
 * 为什么不直接依赖 [TransactionRepository]？
 * - **接口隔离**：导入器只做三件事——查单号、查同金额邻近记录、批量写。给它整个
 *   Repository（包含 Flow 订阅、更新、删除）会让它的依赖面变大，测试也要跟着造一堆无关的假实现。
 * - **可测试**：纯 JVM 单测里用一个 10 行的 fake 就能覆盖去重逻辑，不需要拉起 Room。
 *
 * 嵌入式类比：驱动只依赖它实际用到的那几个寄存器操作，而不是整个外设的 HAL 接口。
 */
interface TransactionStore {

    /**
     * 批量插入。返回每行的 rowId，被唯一索引挡下的行返回 -1。
     * 顺序与传入的 list 一一对应。
     */
    suspend fun insertAll(transactions: List<TransactionEntity>): List<Long>

    /** 按平台单号查重（第一层去重）。 */
    suspend fun findByExternalId(externalId: String): TransactionEntity?

    /**
     * 在 [centerMs] ± [windowMs] 内找同来源、同金额的记录（第二层去重）。
     * 用于识别"这笔账单其实已经被通知通道抓过了"。
     */
    suspend fun findSameAmountNear(
        source: String,
        amountCents: Long,
        centerMs: Long,
        windowMs: Long,
    ): TransactionEntity?
}
