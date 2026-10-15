package com.bookkeeping.app.di

import com.bookkeeping.app.data.repository.TransactionRepository
import com.bookkeeping.app.data.repository.TransactionStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 数据层接口绑定。
 *
 * [TransactionRepository] 同时是具体类和 [TransactionStore] 的实现。账单导入器只依赖后者
 * （它只需要"查单号 / 查邻近同额 / 批量写"三件事），所以这里把接口绑到实现上，
 * 顺带让导入逻辑可以在纯 JVM 单测里替换成假实现。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindTransactionStore(impl: TransactionRepository): TransactionStore
}
