package com.wrbug.polymarketbot.service.backtest

import com.wrbug.polymarketbot.dto.BacktestAuditEventListRequest
import com.wrbug.polymarketbot.dto.BacktestCompareRequest
import com.wrbug.polymarketbot.entity.BacktestAuditEvent
import com.wrbug.polymarketbot.entity.BacktestTask
import com.wrbug.polymarketbot.entity.Leader
import com.wrbug.polymarketbot.repository.BacktestAuditEventRepository
import com.wrbug.polymarketbot.repository.BacktestTaskRepository
import com.wrbug.polymarketbot.repository.BacktestTradeRepository
import com.wrbug.polymarketbot.repository.LeaderRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import java.math.BigDecimal
import java.util.Optional

class BacktestServiceTest {

    private val backtestTaskRepository = mock(BacktestTaskRepository::class.java)
    private val backtestTradeRepository = mock(BacktestTradeRepository::class.java)
    private val backtestAuditEventRepository = mock(BacktestAuditEventRepository::class.java)
    private val leaderRepository = mock(LeaderRepository::class.java)
    private val service = BacktestService(
        backtestTaskRepository = backtestTaskRepository,
        backtestTradeRepository = backtestTradeRepository,
        backtestAuditEventRepository = backtestAuditEventRepository,
        leaderRepository = leaderRepository
    )

    @Test
    fun `compare should reject non-completed tasks`() {
        val completed = task(id = 1L, status = "COMPLETED")
        val running = task(id = 2L, status = "RUNNING")
        `when`(backtestTaskRepository.findAllById(listOf(1L, 2L))).thenReturn(listOf(completed, running))

        val result = service.compareBacktestTasks(BacktestCompareRequest(taskIds = listOf(1L, 2L)))

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("仅支持比较已完成的回测任务") == true)
    }

    @Test
    fun `compare should keep request order and expose config differences`() {
        val task1 = task(
            id = 1L,
            status = "COMPLETED",
            leaderId = 10L,
            taskName = "alpha",
            copyMode = "RATIO",
            profitAmount = BigDecimal("12"),
            profitRate = BigDecimal("6"),
            maxDrawdown = BigDecimal("3"),
            winRate = BigDecimal("55")
        )
        val task2 = task(
            id = 2L,
            status = "COMPLETED",
            leaderId = 10L,
            taskName = "beta",
            copyMode = "FIXED",
            fixedAmount = BigDecimal("20"),
            profitAmount = BigDecimal("9"),
            profitRate = BigDecimal("4"),
            maxDrawdown = BigDecimal("2"),
            winRate = BigDecimal("60")
        )
        `when`(backtestTaskRepository.findAllById(listOf(1L, 2L))).thenReturn(listOf(task2, task1))
        `when`(leaderRepository.findAllById(listOf(10L))).thenReturn(listOf(leader(id = 10L)))

        val response = service.compareBacktestTasks(BacktestCompareRequest(taskIds = listOf(1L, 2L))).getOrThrow()

        assertEquals(listOf(1L, 2L), response.list.map { it.task.id })
        assertTrue(response.configDifferences.any { it.field == "copyMode" })
        assertTrue(response.summary.notes.isNotEmpty())
        assertNotNull(response.summary.whyChain)
    }

    @Test
    fun `audit events should normalize filters and build summary`() {
        val task = task(id = 8L, status = "COMPLETED", leaderId = 99L)
        val pageable = PageRequest.of(0, 500, Sort.by(Sort.Order.asc("id")))
        val pageEvent = BacktestAuditEvent(
            id = 101L,
            backtestTaskId = 8L,
            stage = "FILTER",
            eventType = "RULE_REJECTED",
            decision = "PASS",
            createdAt = 1_700_000_000_000
        )
        val latestEvent = BacktestAuditEvent(
            id = 202L,
            backtestTaskId = 8L,
            stage = "EXECUTE",
            eventType = "ORDER_CREATED",
            decision = "PASS",
            createdAt = 1_700_000_100_000
        )
        `when`(backtestTaskRepository.findById(8L)).thenReturn(Optional.of(task))
        `when`(
            backtestAuditEventRepository.findByTaskIdWithFilters(
                8L,
                "FILTER",
                "PASS",
                "RULE_REJECTED",
                pageable
            )
        ).thenReturn(PageImpl(listOf(pageEvent), pageable, 1))
        `when`(backtestAuditEventRepository.countByBacktestTaskId(8L)).thenReturn(10L)
        `when`(backtestAuditEventRepository.countByBacktestTaskIdAndDecision(8L, "PASS")).thenReturn(7L)
        `when`(backtestAuditEventRepository.countByBacktestTaskIdAndDecision(8L, "SKIP")).thenReturn(2L)
        `when`(backtestAuditEventRepository.countByBacktestTaskIdAndDecision(8L, "ERROR")).thenReturn(1L)
        `when`(backtestAuditEventRepository.countByBacktestTaskIdAndDecision(8L, "STOP")).thenReturn(0L)
        `when`(backtestAuditEventRepository.countByTaskIdGroupByStage(8L)).thenReturn(
            listOf(
                arrayOf("FILTER", 4L),
                arrayOf("EXECUTE", 6L)
            )
        )
        `when`(backtestAuditEventRepository.findTop200ByBacktestTaskIdOrderByIdDesc(8L)).thenReturn(listOf(latestEvent))

        val response = service.getBacktestAuditEvents(
            BacktestAuditEventListRequest(
                taskId = 8L,
                page = 0,
                size = 999,
                stage = " FILTER ",
                decision = " pass ",
                eventType = " RULE_REJECTED "
            )
        ).getOrThrow()

        assertEquals(1, response.page)
        assertEquals(500, response.size)
        assertEquals(1, response.list.size)
        assertEquals(10L, response.summary.totalEvents)
        assertEquals(7L, response.summary.passEvents)
        assertEquals(1_700_000_100_000, response.summary.latestEventAt)
        verify(backtestAuditEventRepository).findByTaskIdWithFilters(
            8L,
            "FILTER",
            "PASS",
            "RULE_REJECTED",
            pageable
        )
    }

    private fun leader(id: Long) = Leader(
        id = id,
        leaderAddress = "0x1234567890123456789012345678901234567890",
        leaderName = "leader-$id"
    )

    private fun task(
        id: Long,
        status: String,
        leaderId: Long = 1L,
        taskName: String = "task-$id",
        copyMode: String = "RATIO",
        fixedAmount: BigDecimal? = null,
        profitAmount: BigDecimal? = null,
        profitRate: BigDecimal? = null,
        maxDrawdown: BigDecimal? = null,
        winRate: BigDecimal? = null
    ) = BacktestTask(
        id = id,
        taskName = taskName,
        leaderId = leaderId,
        initialBalance = BigDecimal("1000"),
        backtestDays = 7,
        startTime = 1_700_000_000_000,
        copyMode = copyMode,
        fixedAmount = fixedAmount,
        status = status
    ).apply {
        this.profitAmount = profitAmount
        this.profitRate = profitRate
        this.maxDrawdown = maxDrawdown
        this.winRate = winRate
        this.totalTrades = 20
        this.buyTrades = 12
        this.sellTrades = 8
        this.winTrades = 11
        this.lossTrades = 9
    }
}
