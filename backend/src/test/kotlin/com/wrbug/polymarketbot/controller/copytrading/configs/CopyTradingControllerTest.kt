package com.wrbug.polymarketbot.controller.copytrading.configs

import com.wrbug.polymarketbot.dto.CopyTradingExecutionEventListRequest
import com.wrbug.polymarketbot.dto.CopyTradingExecutionEventListResponse
import com.wrbug.polymarketbot.dto.ApiResponse
import com.wrbug.polymarketbot.enums.ErrorCode
import com.wrbug.polymarketbot.service.copytrading.configs.CopyTradingService
import com.wrbug.polymarketbot.service.copytrading.configs.FilteredOrderService
import com.wrbug.polymarketbot.service.copytrading.observability.CopyTradingExecutionEventService
import com.wrbug.polymarketbot.service.copytrading.statistics.CopyOrderTrackingService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.context.MessageSource

class CopyTradingControllerTest {

    private val copyTradingService = mock(CopyTradingService::class.java)
    private val filteredOrderService = mock(FilteredOrderService::class.java)
    private val executionEventService = mock(CopyTradingExecutionEventService::class.java)
    private val copyOrderTrackingService = mock(CopyOrderTrackingService::class.java)
    private val messageSource = mock(MessageSource::class.java)
    private val controller = CopyTradingController(
        copyTradingService = copyTradingService,
        filteredOrderService = filteredOrderService,
        executionEventService = executionEventService,
        copyOrderTrackingService = copyOrderTrackingService,
        messageSource = messageSource
    )

    @Test
    fun `getExecutionEvents should reject minLatencyMs without latencyMetric`() {
        val response = controller.getExecutionEvents(
            CopyTradingExecutionEventListRequest(
                copyTradingId = 1L,
                minLatencyMs = 300
            )
        )

        assertError(response.body, ErrorCode.PARAM_ERROR.code, "使用最小耗时阈值时必须指定耗时指标")
        verifyNoInteractions(executionEventService)
    }

    @Test
    fun `getExecutionEvents should reject unsupported latencyMetric`() {
        val response = controller.getExecutionEvents(
            CopyTradingExecutionEventListRequest(
                copyTradingId = 1L,
                latencyMetric = "badMetric"
            )
        )

        assertError(response.body, ErrorCode.PARAM_ERROR.code, "不支持的耗时指标: badMetric")
        verifyNoInteractions(executionEventService)
    }

    @Test
    fun `getExecutionEvents should trim latencyMetric before calling service`() {
        val expectedRequest = CopyTradingExecutionEventListRequest(
            copyTradingId = 1L,
            latencyMetric = "sourceToOrderCompleteMs",
            page = 1,
            limit = 20
        )
        val serviceResponse = CopyTradingExecutionEventListResponse(
            list = emptyList(),
            total = 0,
            page = 1,
            limit = 20
        )
        `when`(executionEventService.getEvents(expectedRequest))
            .thenReturn(serviceResponse)

        val response = controller.getExecutionEvents(
            CopyTradingExecutionEventListRequest(
                copyTradingId = 1L,
                latencyMetric = " sourceToOrderCompleteMs ",
                page = 1,
                limit = 20
            )
        )

        verify(executionEventService).getEvents(expectedRequest)
        assertEquals(0, response.body?.code)
        assertEquals(0L, response.body?.data?.total)
    }

    @Test
    fun `getExecutionEvents should reject reversed time range`() {
        val response = controller.getExecutionEvents(
            CopyTradingExecutionEventListRequest(
                copyTradingId = 1L,
                startTime = 200L,
                endTime = 100L
            )
        )

        assertError(response.body, ErrorCode.PARAM_ERROR.code, "开始时间不能大于结束时间")
        verifyNoInteractions(executionEventService)
    }

    private fun assertError(body: ApiResponse<CopyTradingExecutionEventListResponse>?, code: Int, message: String) {
        assertNotNull(body)
        assertEquals(code, body?.code)
        assertEquals(message, body?.msg)
    }
}
