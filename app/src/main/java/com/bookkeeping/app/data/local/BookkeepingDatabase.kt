package com.bookkeeping.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.bookkeeping.app.data.local.dao.TransactionDao
import com.bookkeeping.app.data.local.entity.TransactionEntity

// Room 数据库本体。
// entities = 这个库里有哪些"表"
// version = 数据库版本号；每次改 Entity 结构需要 +1 并写 Migration
@Database(
    entities = [TransactionEntity::class],
    version = 2,
    exportSchema = false  // 不导出 schema 文件；正式上线建议开
)
abstract class BookkeepingDatabase : RoomDatabase() {
    // 暴露 DAO 给外部使用。Room 会自动生成实现。
    abstract fun transactionDao(): TransactionDao

    companion object {
        const val DB_NAME = "bookkeeping.db"

        /**
         * v1 → v2：账单导入需要给每笔交易带一个"平台单号"用来去重。
         *
         * 为什么必须是正式 Migration、不能再用 fallbackToDestructiveMigration：
         * 破坏性迁移会把整张 transactions 表删掉重建。用户是先用通知/短信通道记了
         * 一段时间账，再来导入历史账单的——升级这一刻他手里是有数据的，丢不起。
         *
         * 加的是一个可空列，SQLite 用 ALTER TABLE 就能原地加，不需要重建表。
         * 唯一索引名必须和 Room 自己生成的一致（index_<表名>_<列名>），否则
         * 在 v2 上新建库（走 Room 的 createAllTables）和从 v1 升级上来的库，
         * 索引名会不一样，下次改表结构时校验就会失败。
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `transactions` ADD COLUMN `externalId` TEXT")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_transactions_externalId` " +
                        "ON `transactions` (`externalId`)"
                )
            }
        }
    }
}
