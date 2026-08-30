package com.llmhub.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.llmhub.app.data.db.dao.*
import com.llmhub.app.data.model.*

@Database(
    entities = [
        ChatSession::class, ChatMessage::class, ModelConfig::class, ApiKeyConfig::class,
        UsageRecord::class, RemoteUsageSnapshot::class, RemoteDailyPoint::class,
    ],
    version = 3,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class LlmHubDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
    abstract fun modelConfigDao(): ModelConfigDao
    abstract fun apiKeyDao(): ApiKeyDao
    abstract fun usageDao(): UsageDao
    abstract fun remoteUsageDao(): RemoteUsageDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val hasBillingCol = db.query("PRAGMA table_info(model_configs)").use { cur ->
                    var found = false
                    while (cur.moveToNext()) {
                        if (cur.getString(cur.getColumnIndexOrThrow("name")) == "billingEndpointKind") { found = true; break }
                    }
                    found
                }
                if (!hasBillingCol) {
                    db.execSQL("ALTER TABLE model_configs ADD COLUMN billingEndpointKind TEXT NOT NULL DEFAULT 'DISABLED'")
                }
                db.execSQL("""
                    UPDATE model_configs SET billingEndpointKind = CASE
                        WHEN provider = 'OPENAI' THEN 'OPENAI_OFFICIAL'
                        WHEN provider = 'DEEPSEEK' THEN 'DEEPSEEK'
                        WHEN provider = 'QWEN' THEN 'DASHSCOPE'
                        ELSE 'DISABLED' END
                    WHERE billingEndpointKind IS NULL OR billingEndpointKind = '' OR billingEndpointKind = 'DISABLED'
                """.trimIndent())
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `remote_usage_snapshots` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `apiKeyRefId` INTEGER NOT NULL,
                        `rangeStartMillis` INTEGER NOT NULL,
                        `rangeEndMillis` INTEGER NOT NULL,
                        `fetchedAtDayBucket` INTEGER NOT NULL,
                        `fetchedAtMillis` INTEGER NOT NULL,
                        `status` TEXT NOT NULL,
                        `errorMessage` TEXT,
                        `totalRequests` INTEGER NOT NULL,
                        `totalInputTokens` INTEGER NOT NULL,
                        `totalOutputTokens` INTEGER NOT NULL,
                        `totalCostAmount` REAL NOT NULL,
                        `totalCostCurrency` TEXT NOT NULL,
                        FOREIGN KEY(`apiKeyRefId`) REFERENCES `api_keys`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )""".trimIndent())
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_remote_usage_snapshots_apiKeyRefId_fetchedAtDayBucket` ON `remote_usage_snapshots` (`apiKeyRefId`, `fetchedAtDayBucket`)")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `remote_daily_points` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `snapshotId` INTEGER NOT NULL,
                        `modelId` TEXT NOT NULL,
                        `dateBucket` INTEGER NOT NULL,
                        `requests` INTEGER NOT NULL,
                        `inputTokens` INTEGER NOT NULL,
                        `outputTokens` INTEGER NOT NULL,
                        `costAmount` REAL NOT NULL,
                        `costCurrency` TEXT NOT NULL,
                        FOREIGN KEY(`snapshotId`) REFERENCES `remote_usage_snapshots`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )""".trimIndent())
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_remote_daily_points_snapshotId_dateBucket_modelId` ON `remote_daily_points` (`snapshotId`, `dateBucket`, `modelId`)")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                fun hasColumn(table: String, column: String): Boolean =
                    db.query("PRAGMA table_info($table)").use { cur ->
                        var found = false
                        while (cur.moveToNext()) {
                            if (cur.getString(cur.getColumnIndexOrThrow("name")) == column) { found = true; break }
                        }
                        found
                    }
                if (!hasColumn("remote_usage_snapshots", "source")) {
                    db.execSQL("ALTER TABLE remote_usage_snapshots ADD COLUMN source TEXT NOT NULL DEFAULT 'API'")
                }
                if (!hasColumn("remote_usage_snapshots", "note")) {
                    db.execSQL("ALTER TABLE remote_usage_snapshots ADD COLUMN note TEXT")
                }
            }
        }

        val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
    }
}

// 软件签名：TRAE AI 开发环境
// 大模型签名：Trae 智能助手
