package com.bookkeeping.app.di

import com.bookkeeping.app.importer.BillArchiveReader
import com.bookkeeping.app.importer.BillReader
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

// 把 BillReader 这个接口绑到真实实现上。
// 用 @Binds 而不是在 BillArchiveReader 上直接 @Inject：这样 BillImporter 只认接口，
// 单元测试可以塞一个假的 reader，不需要 Android 运行时。
@Module
@InstallIn(SingletonComponent::class)
abstract class ImporterModule {

    @Binds
    @Singleton
    abstract fun bindBillReader(impl: BillArchiveReader): BillReader
}
