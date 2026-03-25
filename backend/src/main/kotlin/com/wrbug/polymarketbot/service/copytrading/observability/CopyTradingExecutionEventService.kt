package com.wrbug.polymarketbot.service.copytrading.observability

import com.wrbug.polymarketbot.dto.CopyTradingExecutionEventDto
import com.wrbug.polymarketbot.dto.CopyTradingExecutionEventListRequest
import com.wrbug.polymarketbot.dto.CopyTradingExecutionEventListResponse
import com.wrbug.polymarketbot.entity.CopyTradingExecutionEvent
import com.wrbug.polymarketbot.repository.AccountRepository
import com.wrbug.polymarketbot.repository.CopyTradingExecutionEventRepository
import com.wrbug.polymarketbot.repository.LeaderRepository
import com.wrbug.polymarketbot.service.common.MarketService
import com.wrbug.polymarketbot.util.fromJson
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import java.math.BigDecimal

data class CopyTradingExecutionEventRecordRequest(
    val copyTradingId: Long,
    val accountId: Long,
    val leaderId: Long,
    val leaderTradeId: String? = null,
    val marketId: String? = null,
    val side: String? = null,
    val outcomeIndex: Int? = null,
    val outcome: String? = null,
    val source: String? = null,
    val stage: String,
    val eventType: String,
    val status: String,
    val leaderPrice: BigDecimal? = null,
    val leaderQuantity: BigDecimal? = null,
    val leaderOrderAmount: BigDecimal? = null,
    val calculatedQuantity: BigDecimal? = null,
    val orderPrice: BigDecimal? = null,
    val orderQuantity: BigDecimal? = null,
    val orderId: String? = null,
    val aggregationKey: String? = null,
    val aggregationTradeCount: Int? = null,
    val message: String,
    val detailJson: String? = null
)

@Service
class CopyTradingExecutionEventService(
    private val executionEventRepository: CopyTradingExecutionEventRepository,
    private val accountRepository: AccountRepository,
    private val leaderRepository: LeaderRepository,
    private val marketService: MarketService
) {

    private val logger = LoggerFactory.getLogger(CopyTradingExecutionEventService::class.java)

    companion object {
        private const val LATENCY_MARKET_META_RESOLVE_MS = "marketMetaResolveMs"
        private const val LATENCY_FILTER_EVALUATE_MS = "filterEvaluateMs"
        private const val LATENCY_DIAGNOSTICS_MS = "diagnosticsMs"
        private const val LATENCY_ORDERBOOK_FETCH_MS = "orderbookFetchMs"
        private const val LATENCY_API_SECRET_DECRYPT_MS = "apiSecretDecryptMs"
        private const val LATENCY_API_PASSPHRASE_DECRYPT_MS = "apiPassphraseDecryptMs"
        private const val LATENCY_PRIVATE_KEY_DECRYPT_MS = "privateKeyDecryptMs"
        private const val LATENCY_FEE_RATE_FETCH_MS = "feeRateFetchMs"
        private const val LATENCY_NEG_RISK_RESOLVE_MS = "negRiskResolveMs"
        private const val LATENCY_SOURCE_TO_PROCESS_MS = "sourceToProcessMs"
        private const val LATENCY_PROCESS_TO_ORDER_REQUEST_MS = "processToOrderRequestMs"
        private const val LATENCY_ORDER_CREATE_DURATION_MS = "orderCreateDurationMs"
        private const val LATENCY_SOURCE_TO_ORDER_COMPLETE_MS = "sourceToOrderCompleteMs"
        val SUPPORTED_LATENCY_METRICS = setOf(
            LATENCY_MARKET_META_RESOLVE_MS,
            LATENCY_FILTER_EVALUATE_MS,
            LATENCY_DIAGNOSTICS_MS,
            LATENCY_ORDERBOOK_FETCH_MS,
            LATENCY_API_SECRET_DECRYPT_MS,
            LATENCY_API_PASSPHRASE_DECRYPT_MS,
            LATENCY_PRIVATE_KEY_DECRYPT_MS,
            LATENCY_FEE_RATE_FETCH_MS,
            LATENCY_NEG_RISK_RESOLVE_MS,
            LATENCY_SOURCE_TO_PROCESS_MS,
            LATENCY_PROCESS_TO_ORDER_REQUEST_MS,
            LATENCY_ORDER_CREATE_DURATION_MS,
            LATENCY_SOURCE_TO_ORDER_COMPLETE_MS
        )
    }

    private data class EventWithLatency(
        val event: CopyTradingExecutionEvent,
        val latencyValue: Long?
    )

    fun recordEvent(request: CopyTradingExecutionEventRecordRequest) {
        try {
            executionEventRepository.save(
                CopyTradingExecutionEvent(
                    copyTradingId = request.copyTradingId,
                    accountId = request.accountId,
                    leaderId = request.leaderId,
                    leaderTradeId = request.leaderTradeId,
                    marketId = request.marketId,
                    side = request.side,
                    outcomeIndex = request.outcomeIndex,
                    outcome = request.outcome,
                    source = request.source,
                    stage = request.stage,
                    eventType = request.eventType,
                    status = request.status,
                    leaderPrice = request.leaderPrice,
                    leaderQuantity = request.leaderQuantity,
                    leaderOrderAmount = request.leaderOrderAmount,
                    calculatedQuantity = request.calculatedQuantity,
                    orderPrice = request.orderPrice,
                    orderQuantity = request.orderQuantity,
                    orderId = request.orderId,
                    aggregationKey = request.aggregationKey,
                    aggregationTradeCount = request.aggregationTradeCount,
                    message = request.message,
                    detailJson = request.detailJson
                )
            )
        } catch (e: Exception) {
            logger.warn("记录执行事件失败: copyTradingId={}, eventType={}", request.copyTradingId, request.eventType, e)
        }
    }

    fun getEvents(request: CopyTradingExecutionEventListRequest): CopyTradingExecutionEventListResponse {
        val page = (request.page ?: 1).coerceAtLeast(1)
        val limit = (request.limit ?: 20).coerceAtLeast(1).coerceAtMost(100)
        val latencyMetricKey = normalizeLatencyMetric(request.latencyMetric)
        val hasLatencyFiltering = latencyMetricKey != null || request.minLatencyMs != null

        val events: List<CopyTradingExecutionEvent>
        val total: Long

        if (!hasLatencyFiltering) {
            val pageable = PageRequest.of(page - 1, limit, Sort.by(Sort.Direction.DESC, "createdAt"))
            val pageResult = executionEventRepository.search(
                copyTradingId = request.copyTradingId,
                eventType = request.eventType,
                stage = request.stage,
                source = request.source,
                status = request.status,
                startTime = request.startTime,
                endTime = request.endTime,
                pageable = pageable
            )
            events = pageResult.content
            total = pageResult.totalElements
        } else {
            val filteredEvents = executionEventRepository.searchAll(
                copyTradingId = request.copyTradingId,
                eventType = request.eventType,
                stage = request.stage,
                source = request.source,
                status = request.status,
                startTime = request.startTime,
                endTime = request.endTime
            ).map { event ->
                EventWithLatency(
                    event = event,
                    latencyValue = extractLatencyValue(event.detailJson, latencyMetricKey)
                )
            }.filter { eventWithLatency ->
                val minLatencyMs = request.minLatencyMs
                if (minLatencyMs == null) {
                    true
                } else {
                    (eventWithLatency.latencyValue ?: Long.MIN_VALUE) >= minLatencyMs
                }
            }.let { eventList ->
                if (latencyMetricKey == null) {
                    eventList
                } else {
                    eventList.sortedWith(
                        compareByDescending<EventWithLatency> { it.latencyValue ?: Long.MIN_VALUE }
                            .thenByDescending { it.event.createdAt }
                    )
                }
            }

            total = filteredEvents.size.toLong()
            val fromIndex = ((page - 1) * limit).coerceAtMost(filteredEvents.size)
            val toIndex = (fromIndex + limit).coerceAtMost(filteredEvents.size)
            events = filteredEvents.subList(fromIndex, toIndex).map { it.event }
        }

        val list = events.map { event ->
            val accountName = accountRepository.findById(event.accountId).orElse(null)?.accountName
            val leaderName = leaderRepository.findById(event.leaderId).orElse(null)?.leaderName
            val marketTitle = event.marketId?.let { marketId ->
                runCatching { marketService.getMarket(marketId)?.title }.getOrNull()
            }
            CopyTradingExecutionEventDto(
                id = event.id!!,
                copyTradingId = event.copyTradingId,
                accountId = event.accountId,
                accountName = accountName,
                leaderId = event.leaderId,
                leaderName = leaderName,
                leaderTradeId = event.leaderTradeId,
                marketId = event.marketId,
                marketTitle = marketTitle,
                side = event.side,
                outcomeIndex = event.outcomeIndex,
                outcome = event.outcome,
                source = event.source,
                stage = event.stage,
                eventType = event.eventType,
                status = event.status,
                leaderPrice = event.leaderPrice?.toPlainString(),
                leaderQuantity = event.leaderQuantity?.toPlainString(),
                leaderOrderAmount = event.leaderOrderAmount?.toPlainString(),
                calculatedQuantity = event.calculatedQuantity?.toPlainString(),
                orderPrice = event.orderPrice?.toPlainString(),
                orderQuantity = event.orderQuantity?.toPlainString(),
                orderId = event.orderId,
                aggregationKey = event.aggregationKey,
                aggregationTradeCount = event.aggregationTradeCount,
                message = event.message,
                detailJson = event.detailJson,
                createdAt = event.createdAt
            )
        }
        return CopyTradingExecutionEventListResponse(
            list = list,
            total = total,
            page = page,
            limit = limit
        )
    }

    private fun normalizeLatencyMetric(metric: String?): String? {
        val normalizedMetric = metric?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return normalizedMetric.takeIf { it in SUPPORTED_LATENCY_METRICS }
    }

    private fun extractLatencyValue(detailJson: String?, metricKey: String?): Long? {
        if (metricKey.isNullOrBlank()) {
            return null
        }

        val detailMap = detailJson.fromJson<Map<String, Any?>>() ?: return null
        val value = detailMap[metricKey] ?: return null
        return when (value) {
            is Number -> value.toLong()
            is String -> value.toLongOrNull()
            else -> null
        }
    }
}
