package com.wrbug.polymarketbot.service.system

import com.wrbug.polymarketbot.repository.BacktestAuditEventRepository
import com.wrbug.polymarketbot.repository.CopyTradingExecutionEventRepository
import com.wrbug.polymarketbot.repository.TraderActivityEventHistoryRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 可观测性与活动历史数据保留清理。
 * 目标：防止事件表无限增长导致磁盘暴涨。
 */
@Service
class ObservabilityDataRetentionCleanupService(
    private val copyTradingExecutionEventRepository: CopyTradingExecutionEventRepository,
    private val backtestAuditEventRepository: BacktestAuditEventRepository,
    private val traderActivityEventHistoryRepository: TraderActivityEventHistoryRepository,
    @Value("\${data.retention.copy-trading-execution-event.days:7}")
    private val copyTradingExecutionEventRetentionDays: Double,
    @Value("\${data.retention.backtest-audit-event.days:14}")
    private val backtestAuditEventRetentionDays: Double,
    @Value("\${data.retention.trader-activity-event-history.days:3}")
    private val traderActivityEventHistoryRetentionDays: Double
) {

    private val logger = LoggerFactory.getLogger(ObservabilityDataRetentionCleanupService::class.java)

    @Scheduled(cron = "\${data.retention.cleanup.cron:0 20 3 * * *}")
    @Transactional
    fun cleanupExpiredObservabilityData() {
        val now = System.currentTimeMillis()
        val executionEventCutoff = now - toMs(copyTradingExecutionEventRetentionDays)
        val auditEventCutoff = now - toMs(backtestAuditEventRetentionDays)
        val traderActivityCutoff = now - toMs(traderActivityEventHistoryRetentionDays)

        val deletedExecutionEvents = copyTradingExecutionEventRepository.deleteByCreatedAtBefore(executionEventCutoff)
        val deletedAuditEvents = backtestAuditEventRepository.deleteByCreatedAtBefore(auditEventCutoff)
        val deletedTraderActivityEvents = traderActivityEventHistoryRepository.deleteByCreatedAtBefore(traderActivityCutoff)

        if (deletedExecutionEvents > 0 || deletedAuditEvents > 0 || deletedTraderActivityEvents > 0) {
            logger.info(
                "数据保留清理完成: copyTradingExecutionEventDeleted={}, backtestAuditEventDeleted={}, traderActivityEventDeleted={}",
                deletedExecutionEvents,
                deletedAuditEvents,
                deletedTraderActivityEvents
            )
        }
    }

    private fun toMs(days: Double): Long {
        val safeDays = days.coerceAtLeast(0.01)
        return (safeDays * 24 * 60 * 60 * 1000).toLong()
    }
}
