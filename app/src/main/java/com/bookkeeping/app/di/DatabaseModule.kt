package com.bookkeeping.app.di

import android.content.Context
import androidx.room.Room
import com.bookkeeping.app.data.local.BookkeepingDatabase
import com.bookkeeping.app.data.local.dao.TransactionDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

// Hilt Module：告诉 Hilt "如何创建" Database 和 DAO 实例。
// @InstallIn(SingletonComponent::class) = 全 app 单例。
// 嵌入式类比：在系统启动时初始化外设句柄，整个 app 共享同一份。
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideBookkeepingDatabase(
        @ApplicationContext context: Context
    ): BookkeepingDatabase {
        return Room.databaseBuilder(
            context,
            BookkeepingDatabase::class.java,
            BookkeepingDatabase.DB_NAME
        )
            // 正式迁移：升级时保住用户已经记下的账，绝不清库重建。
            // 每新增一版结构就 addMigrations 追加一个，v1→v3 时 Room 会自动串联执行。
            .addMigrations(BookkeepingDatabase.MIGRATION_1_2)
            .build()
    }

    @Provides
    fun provideTransactionDao(db: BookkeepingDatabase): TransactionDao = db.transactionDao()
}
