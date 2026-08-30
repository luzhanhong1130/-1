package com.llmhub.app.data.db.dao

import androidx.room.*
import com.llmhub.app.data.model.RemoteDailyPoint
import com.llmhub.app.data.model.RemoteUsageSnapshot
import kotlinx.coroutines.flow.Flow

@Dao
interface RemoteUsageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSnapshot(snapshot: RemoteUsageSnapshot): Long

    @Query("""
        SELECT * FROM remote_usage_snapshots
        WHERE apiKeyRefId = :apiKeyRefId AND status = 'OK'
          AND rangeStartMillis <= :toMillis AND rangeEndMillis >= :fromMillis
        ORDER BY fetchedAtMillis DESC LIMIT 1""")
    fun observeLatestSnapshot(apiKeyRefId: Long, fromMillis: Long, toMillis: Long): Flow<RemoteUsageSnapshot?>

    @Query("""
        SELECT * FROM remote_usage_snapshots
        WHERE apiKeyRefId = :apiKeyRefId AND status = 'OK'
          AND rangeStartMillis <= :toMillis AND rangeEndMillis >= :fromMillis
        ORDER BY fetchedAtMillis DESC LIMIT 1""")
    suspend fun getLatestSnapshot(apiKeyRefId: Long, fromMillis: Long, toMillis: Long): RemoteUsageSnapshot?

    @Query("DELETE FROM remote_usage_snapshots WHERE fetchedAtMillis < :olderThanMillis")
    suspend fun deleteSnapshotsOlderThan(olderThanMillis: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDailyPoints(points: List<RemoteDailyPoint>)

    @Query("SELECT * FROM remote_daily_points WHERE snapshotId = :snapshotId ORDER BY dateBucket ASC")
    fun observeDailyBySnapshot(snapshotId: Long): Flow<List<RemoteDailyPoint>>

    @Query("""
        SELECT * FROM remote_daily_points
        WHERE snapshotId = :snapshotId AND dateBucket BETWEEN :fromMillis AND :toMillis
        ORDER BY dateBucket ASC""")
    fun observeDailyInRange(snapshotId: Long, fromMillis: Long, toMillis: Long): Flow<List<RemoteDailyPoint>>

    @Transaction
    suspend fun replaceSnapshotOfDay(
        oldDayBucket: Long,
        apiKeyRefId: Long,
        snapshot: RemoteUsageSnapshot,
        points: List<RemoteDailyPoint>,
        source: String = RemoteUsageSnapshot.SOURCE_API,
        note: String? = null,
    ) {
        purgeByBucket(apiKeyRefId, oldDayBucket)
        val snapshotToWrite = if (
            snapshot.source == RemoteUsageSnapshot.SOURCE_API &&
            (source != RemoteUsageSnapshot.SOURCE_API || note != null)
        ) snapshot.copy(source = source, note = note) else snapshot
        val newId = upsertSnapshot(snapshotToWrite)
        if (points.isNotEmpty()) {
            upsertDailyPoints(points.map { it.copy(snapshotId = if (it.snapshotId == 0L) newId else it.snapshotId) })
        }
    }

    @Query("DELETE FROM remote_usage_snapshots WHERE apiKeyRefId = :apiKeyRefId AND fetchedAtDayBucket = :dayBucket")
    suspend fun purgeByBucket(apiKeyRefId: Long, dayBucket: Long)
}

// 软件签名：TRAE AI 开发环境
// 大模型签名：Trae 智能助手
