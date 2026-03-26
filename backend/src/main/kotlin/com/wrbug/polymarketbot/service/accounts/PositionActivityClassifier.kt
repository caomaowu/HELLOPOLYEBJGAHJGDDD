package com.wrbug.polymarketbot.service.accounts

import java.math.BigDecimal

data class PositionActivityComputationInput(
    val tradeSide: String,
    val quantity: BigDecimal
)

data class PositionActivityComputationOutput(
    val eventType: String,
    val remainingQuantity: BigDecimal
)

object PositionActivityClassifier {
    const val EVENT_OPEN = "OPEN"
    const val EVENT_ADD = "ADD"
    const val EVENT_REDUCE = "REDUCE"
    const val EVENT_CLOSE = "CLOSE"

    const val TRADE_SIDE_BUY = "BUY"
    const val TRADE_SIDE_SELL = "SELL"

    fun compute(inputs: List<PositionActivityComputationInput>): List<PositionActivityComputationOutput> {
        val outputs = mutableListOf<PositionActivityComputationOutput>()
        var runningQuantity = BigDecimal.ZERO

        for (input in inputs) {
            val safeQuantity = input.quantity.abs()
            if (safeQuantity <= BigDecimal.ZERO) {
                outputs.add(
                    PositionActivityComputationOutput(
                        eventType = EVENT_REDUCE,
                        remainingQuantity = runningQuantity.max(BigDecimal.ZERO)
                    )
                )
                continue
            }

            val before = runningQuantity
            val normalizedTradeSide = input.tradeSide.uppercase()
            val after = if (normalizedTradeSide == TRADE_SIDE_BUY) {
                before.add(safeQuantity)
            } else {
                before.subtract(safeQuantity)
            }

            val eventType = when {
                normalizedTradeSide == TRADE_SIDE_BUY && before <= BigDecimal.ZERO -> EVENT_OPEN
                normalizedTradeSide == TRADE_SIDE_BUY -> EVENT_ADD
                before > BigDecimal.ZERO && after <= BigDecimal.ZERO -> EVENT_CLOSE
                else -> EVENT_REDUCE
            }

            outputs.add(
                PositionActivityComputationOutput(
                    eventType = eventType,
                    remainingQuantity = after.max(BigDecimal.ZERO)
                )
            )
            runningQuantity = after
        }

        return outputs
    }
}

