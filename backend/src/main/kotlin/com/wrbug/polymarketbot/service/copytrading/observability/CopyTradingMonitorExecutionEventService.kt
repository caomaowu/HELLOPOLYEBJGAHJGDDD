package com.wrbug.polymarketbot.service.copytrading.observability

import com.wrbug.polymarketbot.repository.CopyTradingRepository
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
                    detailJson = detailJson
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
                detailJson = detailJson
            )
        }
    }
}
