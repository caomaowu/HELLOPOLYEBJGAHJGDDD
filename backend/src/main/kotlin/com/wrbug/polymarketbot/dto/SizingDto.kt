package com.wrbug.polymarketbot.dto

data class MultiplierTierDto(
    val min: String,
    val max: String? = null,
    val multiplier: String
)
