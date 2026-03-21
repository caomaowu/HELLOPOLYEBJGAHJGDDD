package com.wrbug.polymarketbot.service.backtest

import com.wrbug.polymarketbot.entity.BacktestAuditEvent
import com.wrbug.polymarketbot.repository.BacktestAuditEventRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Service
class BacktestAuditTrailService(
    private val backtestAuditEventRepository: BacktestAuditEventRepository
) {
    private val logger = LoggerFactory.getLogger(BacktestAuditTrailService::class.java)

    /**
     * 审计事件与回测主事务解耦，避免主事务失败时事件全部丢失。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun appendEvents(events: List<BacktestAuditEvent>) {
        if (events.isEmpty()) {
            return
        }
        backtestAuditEventRepository.saveAll(events)
        logger.debug("回测审计事件已持久化: count={}", events.size)
    }
}
