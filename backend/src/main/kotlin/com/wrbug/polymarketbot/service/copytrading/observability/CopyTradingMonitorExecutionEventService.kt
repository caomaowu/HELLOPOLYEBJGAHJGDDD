package com.wrbug.polymarketbot.service.copytrading.observability

import com.wrbug.polymarketbot.repository.CopyTradingRepository
import com.wrbug.polymarketbot.util.toJson
import org.springframework.stereotype.Service

@Service
class CopyTradingMonitorExecutionEventService(
    private val copyTradingRepository: CopyTradingRepository,
    private val executionEventService: CopyTradingExecutionEventService
) {

    fun recordForLeader(
        leaderId: Long,
        leaderTradeId: String? = null,
        marketId: String? = null,
        side: String? = null,
        outcomeIndex: Int? = null,
        outcome: String? = null,
        source: String? = null,
        stage: String = "MONITOR",
        eventType: String,
        status: String,
        message: String,
        detailData: Map<String, Any?>? = null,
        detailJson: String? = null
    ) {
        val copyTradings = copyTradingRepository.findByLeaderIdAndEnabledTrue(leaderId)
        for (copyTrading in copyTradings) {
            val copyTradingId = copyTrading.id ?: continue
            executionEventService.recordEvent(
                CopyTradingExecutionEventRecordRequest(
                    copyTradingId = copyTradingId,
                    accountId = copyTrading.accountId,
                    leaderId = leaderId,
                    leaderTradeId = leaderTradeId,
                    marketId = marketId,
                    side = side,
                    outcomeIndex = outcomeIndex,
                    outcome = outcome,
                    source = source,
                    stage = stage,
                    eventType = eventType,
                    status = status,
                    message = message,
                    detailJson = detailJson ?: buildDetailJson(detailData)
                )
            )
        }
    }

    fun recordForLeaders(
        leaderIds: Collection<Long>,
        leaderTradeId: String? = null,
        marketId: String? = null,
        side: String? = null,
        outcomeIndex: Int? = null,
        outcome: String? = null,
        source: String? = null,
        stage: String = "MONITOR",
        eventType: String,
        status: String,
        message: String,
        detailData: Map<String, Any?>? = null,
        detailJson: String? = null
    ) {
        leaderIds.distinct().forEach { leaderId ->
            recordForLeader(
                leaderId = leaderId,
                leaderTradeId = leaderTradeId,
                marketId = marketId,
                side = side,
                outcomeIndex = outcomeIndex,
                outcome = outcome,
                source = source,
                stage = stage,
                eventType = eventType,
                status = status,
                message = message,
                detailData = detailData,
                detailJson = detailJson
            )
        }
    }

    private fun buildDetailJson(detailData: Map<String, Any?>?): String? {
        if (detailData.isNullOrEmpty()) {
            return null
        }
        val normalized = linkedMapOf<String, Any>()
        detailData.forEach { (key, value) ->
            when (value) {
                null -> Unit
                is String -> value.takeIf { it.isNotBlank() }?.let { normalized[key] = it }
                is Collection<*> -> if (value.isNotEmpty()) normalized[key] = value
                else -> normalized[key] = value
            }
        }
        return normalized.takeIf { it.isNotEmpty() }?.toJson()?.ifBlank { null }
    }
}
