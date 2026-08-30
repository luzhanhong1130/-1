package com.llmhub.app.di

import android.content.Context
import androidx.room.Room
import com.llmhub.app.data.db.LlmHubDatabase
import com.llmhub.app.data.db.dao.ApiKeyDao
import com.llmhub.app.data.db.dao.ChatDao
import com.llmhub.app.data.db.dao.ModelConfigDao
import com.llmhub.app.data.db.dao.RemoteUsageDao
import com.llmhub.app.data.db.dao.UsageDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext ctx: Context): LlmHubDatabase =
        Room.databaseBuilder(ctx, LlmHubDatabase::class.java, "llmhub.db")
            .addMigrations(*LlmHubDatabase.MIGRATIONS)
            // 仅在「降级」时清空（开发期方便回滚）；升级必须显式补 Migration，
            // 否则 Room 抛 IllegalStateException，避免用户数据被静默清空。
            .fallbackToDestructiveMigrationOnDowngrade()
            .build()

    @Provides
    fun provideChatDao(db: LlmHubDatabase): ChatDao = db.chatDao()

    @Provides
    fun provideModelConfigDao(db: LlmHubDatabase): ModelConfigDao = db.modelConfigDao()

    @Provides
    fun provideApiKeyDao(db: LlmHubDatabase): ApiKeyDao = db.apiKeyDao()

    @Provides
    fun provideUsageDao(db: LlmHubDatabase): UsageDao = db.usageDao()

    @Provides
    fun provideRemoteUsageDao(db: LlmHubDatabase): RemoteUsageDao = db.remoteUsageDao()
}
