package com.wrbug.polymarketbot.service.accounts

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class PositionActivityClassifierTest {

    @Test
    fun `should classify open add reduce close in order`() {
        val outputs = PositionActivityClassifier.compute(
            listOf(
                PositionActivityComputationInput("BUY", BigDecimal("10")),
                PositionActivityComputationInput("BUY", BigDecimal("5")),
                PositionActivityComputationInput("SELL", BigDecimal("8")),
                PositionActivityComputationInput("SELL", BigDecimal("7"))
            )
        )

        assertEquals(PositionActivityClassifier.EVENT_OPEN, outputs[0].eventType)
        assertEquals(BigDecimal("10"), outputs[0].remainingQuantity)

        assertEquals(PositionActivityClassifier.EVENT_ADD, outputs[1].eventType)
        assertEquals(BigDecimal("15"), outputs[1].remainingQuantity)

        assertEquals(PositionActivityClassifier.EVENT_REDUCE, outputs[2].eventType)
        assertEquals(BigDecimal("7"), outputs[2].remainingQuantity)

        assertEquals(PositionActivityClassifier.EVENT_CLOSE, outputs[3].eventType)
        assertEquals(BigDecimal.ZERO, outputs[3].remainingQuantity)
    }

    @Test
    fun `should treat first sell as reduce when no baseline exists`() {
        val outputs = PositionActivityClassifier.compute(
            listOf(
                PositionActivityComputationInput("SELL", BigDecimal("3"))
            )
        )

        assertEquals(PositionActivityClassifier.EVENT_REDUCE, outputs[0].eventType)
        assertEquals(BigDecimal.ZERO, outputs[0].remainingQuantity)
    }
}

