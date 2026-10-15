package com.bookkeeping.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// 一笔交易在数据库里的"行"。
// @Entity 告诉 Room "这个类对应数据库表"，表名是 transactions。
// externalId 上加唯一索引：账单导入靠它防止同一份账单被导入两次。
@Entity(
    tableName = "transactions",
    indices = [Index(value = ["externalId"], unique = true)],
)
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    // 金额（以"分"为单位）。金融领域标准：避免 Double 精度问题。
    // 例：1234 = 12.34 元
    val amountCents: Long,

    // 支出 / 收入。值是 TransactionType.name（"EXPENSE" / "INCOME"）
    val type: String,

    // 分类。值是 TransactionCategory.name（"FOOD" / "TRANSPORT" / ...）
    val category: String,

    // 商户名（"星巴克"、"麦当劳"），或交易备注。
    val merchant: String = "",

    // 用户自己填的备注
    val note: String = "",

    // 来源。值是 TransactionSource.name（"MANUAL" / "ALIPAY" / ...）
    val source: String,

    // 账户（"现金"/"支付宝"/"招商银行储蓄卡"），可空
    val account: String? = null,

    // 时间戳（毫秒），用于排序、按月统计
    val timestamp: Long,

    // 平台内部唯一单号（微信交易单号 / 支付宝交易号），仅账单导入的记录会有。
    // 普通自动抓取的记录为 null；SQLite 的唯一索引允许多个 null，互不冲突。
    val externalId: String? = null,
)

// 收入还是支出
enum class TransactionType { EXPENSE, INCOME }

// 来源：手动 / 各种自动渠道
enum class TransactionSource { MANUAL, ALIPAY, WECHAT, BANK_SMS, OTHER }

// 分类。先列常用的，后面可以扩展
enum class TransactionCategory(val displayName: String) {
    FOOD("餐饮"),
    TRANSPORT("交通"),
    SHOPPING("购物"),
    ENTERTAINMENT("娱乐"),
    HOUSING("住房"),
    UTILITIES("水电"),
    HEALTH("医疗"),
    EDUCATION("教育"),
    SALARY("工资"),
    TRANSFER("转账"),
    OTHER("其他"),
}
