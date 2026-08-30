package com.llmhub.app.data.repository

import com.llmhub.app.data.db.dao.UsageDao
import com.llmhub.app.data.model.DailyUsagePoint
import com.llmhub.app.data.model.ModelDetailStat
import com.llmhub.app.data.model.ModelUsageStat
import com.llmhub.app.data.model.SuccessBreakdown
import com.llmhub.app.data.model.UsageSummary
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UsageRepository @Inject constructor(
    private val dao: UsageDao,
) {
    /** 时间范围起点对应的 timestamp（毫秒）。0 表示「全部」。 */
    fun observeSummary(from: Long): Flow<UsageSummary> = dao.observeSummary(from)

    fun observeByModel(from: Long): Flow<List<ModelUsageStat>> = dao.observeByModel(from)

    // ------------------------------------------------------------------
    // 单模型详情
    // ------------------------------------------------------------------

    fun observeModelDetail(modelConfigId: Long, from: Long): Flow<ModelDetailStat> =
        dao.observeModelDetail(modelConfigId, from)

    fun observeDailyByModel(modelConfigId: Long, from: Long): Flow<List<DailyUsagePoint>> =
        dao.observeDailyByModel(modelConfigId, from)

    fun observeSuccessBreakdown(modelConfigId: Long, from: Long): Flow<SuccessBreakdown> =
        dao.observeSuccessBreakdown(modelConfigId, from)

    // ------------------------------------------------------------------
    // 费用重算
    // ------------------------------------------------------------------

    /**
     * 用最新单价重算指定模型的所有历史 usage_records.estimatedCostYuan。
     *
     * @return 被更新的行数。典型调用场景：模型编辑页保存且单价发生变化时。
     */
    suspend fun recalculateCostsForModel(
        modelConfigId: Long,
        priceInputPer1k: Double,
        priceOutputPer1k: Double,
    ): Int = dao.recalculateCosts(modelConfigId, priceInputPer1k, priceOutputPer1k)
}
